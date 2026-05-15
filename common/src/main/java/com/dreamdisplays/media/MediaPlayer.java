package com.dreamdisplays.media;

import com.dreamdisplays.Initializer;
import com.dreamdisplays.display.DisplayScreen;
import com.dreamdisplays.ffmpeg.FFmpegBinary;
import com.dreamdisplays.util.ConverterUtil;
import com.dreamdisplays.util.GeneralUtil;
import com.dreamdisplays.ytdlp.YtDlp;
import com.dreamdisplays.ytdlp.YtStream;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import me.inotsleep.utils.logging.LoggingManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

@NullMarked
public class MediaPlayer {

    public static final boolean DEBUG =
            Boolean.getBoolean("dreamdisplays.debug")
                    || "1".equals(System.getenv("DREAMDISPLAYS_DEBUG"))
                    || "true".equalsIgnoreCase(System.getenv("DREAMDISPLAYS_DEBUG"));

    private static final AtomicInteger INIT_THREAD_COUNTER = new AtomicInteger();
    private static final ExecutorService INIT_EXECUTOR =
            Executors.newFixedThreadPool(
                    Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
                    r -> {
                        Thread t = new Thread(r, "MediaPlayer-init-" + INIT_THREAD_COUNTER.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
            );

    private static final long STOP_WAIT_TIMEOUT_SECONDS = 3;
    private static final long STATS_INTERVAL_MS = 2000;
    private static final int MAX_FETCH_RETRIES = 3;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHUNK_BYTES = 44100 * 2 * 2 / 20;
    private static final int AUDIO_LINE_BUFFER_BYTES = AUDIO_CHUNK_BYTES * 10;
    private static final long CLOCK_NOT_STARTED = Long.MIN_VALUE;
    private static final long WATCHDOG_TIMEOUT_NS = 30_000_000_000L;
    private static final long WATCHDOG_CHECK_INTERVAL_MS = 5000;
    private static final int AUDIO_LINE_OPEN_RETRIES = 3;
    private static final long AUDIO_LINE_RETRY_DELAY_MS = 200;
    private static final long[] RETRY_BACKOFF_MS = {1000, 3000, 8000};
    public static boolean captureSamples = true;
    private final AtomicLong samplesIn = new AtomicLong();
    private final AtomicLong framesToGpu = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();
    private final String youtubeUrl;
    private final String lang;
    private final DisplayScreen displayScreen;
    private final String debugLabel;
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicBoolean frameTaskQueued = new AtomicBoolean(false);
    private final AtomicBoolean restartPending = new AtomicBoolean(false);
    private final Object frameLock = new Object();
    private final ExecutorService controlExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MediaPlayer-ctrl");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService frameExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MediaPlayer-frame");
                t.setDaemon(true);
                return t;
            });
    private volatile @Nullable ScheduledExecutorService statsExecutor;
    private volatile @Nullable ScheduledExecutorService watchdogExecutor;
    private final AtomicLong lastFrameReceivedNanos = new AtomicLong(0);
    private volatile @Nullable List<YtStream> availableVideoStreams;
    private volatile @Nullable List<YtStream> availableAudioStreams;
    private volatile @Nullable YtStream currentVideoStream;
    private volatile @Nullable YtStream currentAudioStream;
    private volatile boolean initialized;
    private volatile boolean liveStream;
    private volatile boolean seekable;
    private volatile long durationHintNanos;
    private int lastQuality;
    private volatile int fetchRetries = 0;
    private volatile @Nullable Process videoProcess;
    private volatile @Nullable Process audioProcess;
    private volatile @Nullable Thread videoThread;
    private volatile @Nullable Thread audioThread;
    private volatile @Nullable AtomicBoolean videoStopFlag;
    private volatile @Nullable AtomicBoolean audioStopFlag;
    private volatile @Nullable SourceDataLine currentAudioLine;
    private volatile boolean playing = false;
    private volatile long seekOffsetNanos = 0;
    private volatile long startWallNanos = CLOCK_NOT_STARTED;

    private volatile @Nullable ByteBuffer currentFrameBuffer;
    private volatile int currentFrameWidth = 0;
    private volatile int currentFrameHeight = 0;
    private volatile @Nullable ByteBuffer preparedBuffer;
    private int preparedBufferSize = 0;
    private volatile int lastTexW = 0, lastTexH = 0;
    private volatile int preparedW = 0, preparedH = 0;
    private volatile boolean frameReady = false;

    private volatile double userVolume = Initializer.config.defaultDisplayVolume;
    private volatile double lastAttenuation = 1.0;
    private volatile double currentVolume = Initializer.config.defaultDisplayVolume;
    private volatile double brightness = 1.0;

    public MediaPlayer(String youtubeUrl, String lang, DisplayScreen displayScreen) {
        this.youtubeUrl = youtubeUrl;
        this.displayScreen = displayScreen;
        this.lang = lang;
        this.debugLabel = displayScreen.getUUID() + "/" + Integer.toHexString(System.identityHashCode(this));
        INIT_EXECUTOR.submit(this::initialize);
    }

    public void play() {
        safeExecute(this::doPlay);
    }

    public void pause() {
        safeExecute(this::doPause);
    }

    public void stop() {
        if (terminated.getAndSet(true)) return;
        Future<?> stopFuture = null;
        if (!controlExecutor.isShutdown()) {
            try {
                stopFuture = controlExecutor.submit(this::doStop);
            } catch (RejectedExecutionException ignored) {
            }
        }
        if (stopFuture != null) {
            try {
                stopFuture.get(STOP_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                doStop();
            }
        } else {
            doStop();
        }
        controlExecutor.shutdownNow();
        frameExecutor.shutdownNow();
    }

    public void seekTo(long nanos, boolean fire) {
        safeExecute(() -> doSeek(nanos, fire));
    }

    public void seekRelative(double s) {
        safeExecute(() -> {
            if (!initialized || !seekable) return;
            long cur = getCurrentTime();
            long tgt = Math.max(0, cur + (long) (s * 1e9));
            long dur = Math.max(0, getDuration() - 1);
            if (dur <= 0) return;
            doSeek(Math.min(tgt, dur), true);
        });
    }

    public long getCurrentTime() {
        if (!initialized || !playing) return seekOffsetNanos;
        long start = startWallNanos;
        if (start == CLOCK_NOT_STARTED) return seekOffsetNanos;
        return seekOffsetNanos + (System.nanoTime() - start);
    }

    public long getDuration() {
        if (liveStream) return 0L;
        return durationHintNanos;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isLive() {
        return liveStream;
    }

    public boolean canSeek() {
        return initialized && seekable;
    }

    public boolean isClockRunning() {
        return startWallNanos != CLOCK_NOT_STARTED;
    }

    public void setVolume(double volume) {
        userVolume = Math.max(0, Math.min(2, volume));
        currentVolume = userVolume * lastAttenuation;
    }

    public void setBrightness(double brightness) {
        this.brightness = Math.max(0, Math.min(2, brightness));
    }

    public boolean textureFilled() {
        synchronized (frameLock) {
            return preparedBuffer != null && preparedBuffer.limit() > 0;
        }
    }

    public void updateFrame(GpuTexture texture) {
        synchronized (frameLock) {
            if (preparedBuffer == null || !frameReady) return;

            int w = displayScreen.textureWidth, h = displayScreen.textureHeight;
            if (w != preparedW || h != preparedH) return;

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

            preparedBuffer.rewind();
            int expectedSize = w * h * 4;
            if (preparedBuffer.remaining() < expectedSize) {
                LoggingManager.error(
                        "Buffer underrun: expected " + expectedSize
                                + " bytes, but only " + preparedBuffer.remaining() + " remaining"
                );
                return;
            }

            if (w != lastTexW || h != lastTexH) {
                lastTexW = w;
                lastTexH = h;
            }

            if (!texture.isClosed()) {
                encoder.writeToTexture(
                        texture, preparedBuffer, NativeImage.Format.RGBA,
                        0, 0, 0, 0, texture.getWidth(0), texture.getHeight(0)
                );
            }

            if (DEBUG) framesToGpu.incrementAndGet();
            frameReady = false;
        }
    }

    public List<Integer> getAvailableQualities() {
        if (availableVideoStreams == null) return Collections.emptyList();
        return availableVideoStreams.stream()
                .map(YtStream::getResolution)
                .filter(Objects::nonNull)
                .map(r -> MediaStreamSelector.parseQualityValue(r, Integer.MAX_VALUE))
                .filter(r -> r != Integer.MAX_VALUE)
                .distinct()
                .filter(r -> r <= (Initializer.isPremium ? 2160 : 1080))
                .sorted()
                .collect(Collectors.toList());
    }

    public void setQuality(String quality) {
        safeExecute(() -> changeQuality(quality));
    }

    public void tick(BlockPos playerPos, double maxRadius) {
        if (!initialized) return;
        double dist = displayScreen.getDistanceToScreen(playerPos);
        double attenuation = Math.pow(1.0 - Math.min(1.0, dist / maxRadius), 2);
        if (Math.abs(attenuation - lastAttenuation) > 1e-5) {
            lastAttenuation = attenuation;
            currentVolume = userVolume * attenuation;
        }
    }

    private void initialize() {
        try {
            String videoId = GeneralUtil.extractVideoId(youtubeUrl);
            if (videoId == null || videoId.isEmpty()) {
                LoggingManager.error("Could not extract video ID from URL: " + youtubeUrl);
                displayScreen.errored = true;
                return;
            }

            if (FFmpegBinary.getPath() == null) {
                LoggingManager.error("[MediaPlayer] FFmpeg binary not available");
                displayScreen.errored = true;
                return;
            }

            String cleanUrl = "https://www.youtube.com/watch?v=" + videoId;
            List<YtStream> all = YtDlp.fetch(cleanUrl);
            if (terminated.get()) return;
            if (all.isEmpty()) {
                LoggingManager.error("No streams available for " + cleanUrl);
                displayScreen.errored = true;
                return;
            }

            liveStream = all.stream().anyMatch(YtStream::isLive);
            seekable = !liveStream && all.stream().anyMatch(YtStream::isSeekable);
            durationHintNanos = all.stream()
                    .mapToLong(YtStream::getDurationNanos)
                    .max()
                    .orElse(0L);

            availableVideoStreams = all.stream().filter(YtStream::hasVideo).toList();
            availableAudioStreams = all.stream().filter(YtStream::hasAudio).toList();

            int requestedQuality = MediaStreamSelector.parseQualityValue(displayScreen.getQuality(), 720);
            Optional<YtStream> videoOpt = MediaStreamSelector.pickVideo(availableVideoStreams, requestedQuality)
                    .or(() -> availableVideoStreams.stream().findFirst());
            Optional<YtStream> audioOpt = MediaStreamSelector.pickAudio(availableAudioStreams, lang, videoOpt.orElse(null));
            if (videoOpt.isEmpty() || audioOpt.isEmpty()) {
                LoggingManager.error("No usable streams for " + cleanUrl);
                displayScreen.errored = true;
                return;
            }

            currentVideoStream = videoOpt.get();
            currentAudioStream = audioOpt.get();
            lastQuality = MediaStreamSelector.parseQuality(currentVideoStream);
            fetchRetries = 0;
            initialized = true;

            if (DEBUG) {
                LoggingManager.info("[MP debug " + debugLabel + "] picked video: " + currentVideoStream);
                LoggingManager.info("[MP debug " + debugLabel + "] picked audio: " + currentAudioStream);
                LoggingManager.info("[MP debug " + debugLabel + "] live=" + liveStream
                        + " seekable=" + seekable + " duration=" + durationHintNanos);
                LoggingManager.info("[MP debug " + debugLabel + "] available video count="
                        + availableVideoStreams.size()
                        + " resolutions=" + getAvailableQualities());
                startStatsReporter();
            }

            final YtStream pickedVideo = currentVideoStream;
            final YtStream pickedAudio = currentAudioStream;
            safeExecute(() -> {
                if (!terminated.get()) {
                    startStreams(pickedVideo, pickedAudio, 0);
                }
            });

        } catch (Exception e) {
            LoggingManager.error("Failed to initialize MediaPlayer", e);
            displayScreen.errored = true;
        }
    }

    private void startStreams(YtStream video, YtStream audio, long offsetNanos) {
        if (terminated.get()) return;
        stopStreams();

        String ffmpeg = FFmpegBinary.getPath();
        if (ffmpeg == null) {
            displayScreen.errored = true;
            return;
        }

        seekOffsetNanos = offsetNanos;
        startWallNanos = CLOCK_NOT_STARTED;

        int[] dims = MediaStreamSelector.qualityToDims(lastQuality > 0 ? lastQuality : MediaStreamSelector.parseQuality(video));
        int frameW = dims[0], frameH = dims[1];

        try {
            LoggingManager.info("[MediaPlayer " + debugLabel + "] starting ffmpeg: "
                    + frameW + "x" + frameH + " quality=" + lastQuality
                    + " offset=" + (offsetNanos / 1_000_000L) + "ms"
                    + " videoUrl=" + MediaUtils.truncate(video.getUrl())
                    + " audioUrl=" + MediaUtils.truncate(audio.getUrl()));
            Process vp = MediaProcess.buildVideo(ffmpeg, video.getUrl(), frameW, frameH, offsetNanos);
            Process ap = MediaProcess.buildAudio(ffmpeg, audio.getUrl(), offsetNanos, AUDIO_SAMPLE_RATE);
            videoProcess = vp;
            audioProcess = ap;

            AtomicBoolean vStop = new AtomicBoolean(false);
            AtomicBoolean aStop = new AtomicBoolean(false);
            videoStopFlag = vStop;
            audioStopFlag = aStop;

            Thread vt = new Thread(() -> videoReaderLoop(vp, frameW, frameH, vStop), "MediaPlayer-video");
            vt.setDaemon(true);
            Thread at = new Thread(() -> audioReaderLoop(ap, aStop), "MediaPlayer-audio");
            at.setDaemon(true);
            videoThread = vt;
            audioThread = at;
            vt.start();
            at.start();
            playing = true;
            startWatchdog();
        } catch (IOException e) {
            LoggingManager.error("[MediaPlayer " + debugLabel + "] Failed to start ffmpeg", e);
            displayScreen.errored = true;
        }
    }

    private void stopStreams() {
        playing = false;
        stopWatchdog();
        Process vp = videoProcess, ap = audioProcess;
        Thread vt = videoThread, at = audioThread;
        AtomicBoolean vStop = videoStopFlag, aStop = audioStopFlag;
        videoProcess = null;
        audioProcess = null;
        videoThread = null;
        audioThread = null;
        videoStopFlag = null;
        audioStopFlag = null;

        if (vStop != null) vStop.set(true);
        if (aStop != null) aStop.set(true);

        MediaProcess.gracefulDestroy(vp);
        MediaProcess.gracefulDestroy(ap);

        synchronized (frameLock) {
            frameReady = false;
        }
        frameTaskQueued.set(false);

        if (vt != null && vt != Thread.currentThread()) {
            try {
                vt.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (at != null && at != Thread.currentThread()) {
            try {
                at.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void videoReaderLoop(Process proc, int w, int h, AtomicBoolean stopFlag) {
        int frameSize = w * h * 4;
        byte[] frameData = new byte[frameSize];
        boolean firstFrameLogged = false;

        synchronized (frameLock) {
            if (currentFrameBuffer == null || currentFrameBuffer.capacity() < frameSize) {
                currentFrameBuffer = ByteBuffer.allocateDirect(frameSize).order(ByteOrder.nativeOrder());
            }
        }

        StringBuilder stderrBuf = new StringBuilder();
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (stderrBuf) {
                        stderrBuf.append(line).append('\n');
                    }
                    if (MediaUtils.isInterestingStderr(line)) {
                        LoggingManager.warn("[ffmpeg-v " + debugLabel + "] " + line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "MediaPlayer-vstderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        boolean normalEos = false;
        try (InputStream in = proc.getInputStream()) {
            while (!terminated.get() && !stopFlag.get()) {
                int n = MediaUtils.readFull(in, frameData, frameSize);
                if (n < frameSize) {
                    normalEos = true;
                    break;
                }
                lastFrameReceivedNanos.set(System.nanoTime());
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    startWallNanos = System.nanoTime();
                    LoggingManager.info("[MediaPlayer " + debugLabel + "] first video frame received ("
                            + w + "x" + h + ", " + frameSize + " bytes)");
                }
                if (!captureSamples) continue;

                synchronized (frameLock) {
                    ByteBuffer frame = currentFrameBuffer;
                    if (frame == null || frame.capacity() < frameSize) {
                        frame = ByteBuffer.allocateDirect(frameSize).order(ByteOrder.nativeOrder());
                        currentFrameBuffer = frame;
                    }
                    frame.clear();
                    frame.put(frameData, 0, frameSize);
                    frame.flip();
                    currentFrameWidth = w;
                    currentFrameHeight = h;
                }

                if (DEBUG) samplesIn.incrementAndGet();

                if (!frameTaskQueued.compareAndSet(false, true)) {
                    if (DEBUG) framesDropped.incrementAndGet();
                    continue;
                }
                try {
                    frameExecutor.submit(this::prepareBuffer);
                } catch (RejectedExecutionException e) {
                    frameTaskQueued.set(false);
                }
            }
        } catch (IOException e) {
            if (!terminated.get() && !stopFlag.get()) {
                LoggingManager.warn("[MediaPlayer " + debugLabel + "] Video read error: " + e.getMessage());
            }
        }

        int exitCode = -1;
        if (normalEos) {
            try {
                boolean done = proc.waitFor(500, TimeUnit.MILLISECONDS);
                exitCode = done ? proc.exitValue() : -1;
                if (!done) proc.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (!terminated.get() && !stopFlag.get()) {
            try {
                stderrThread.join(500);
            } catch (InterruptedException ignored) {
            }
            String stderr;
            synchronized (stderrBuf) {
                stderr = stderrBuf.toString();
            }
            handleStreamEnd(stderr, exitCode == 0, true);
        }
    }

    private void audioReaderLoop(Process proc, AtomicBoolean stopFlag) {
        Thread stderrThread = new Thread(() -> {
            try {
                proc.getErrorStream().transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        }, "MediaPlayer-astderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        SourceDataLine line = null;
        try (InputStream in = proc.getInputStream()) {
            AudioFormat fmt = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    AUDIO_SAMPLE_RATE, 16, 2, 4, AUDIO_SAMPLE_RATE, false /* little-endian */);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                LoggingManager.warn("[MediaPlayer " + debugLabel + "] javax.sound: PCM line not supported");
                return;
            }
            for (int attempt = 1; attempt <= AUDIO_LINE_OPEN_RETRIES; attempt++) {
                try {
                    line = (SourceDataLine) AudioSystem.getLine(info);
                    line.open(fmt, AUDIO_LINE_BUFFER_BYTES);
                    break;
                } catch (LineUnavailableException e) {
                    if (attempt == AUDIO_LINE_OPEN_RETRIES) {
                        LoggingManager.warn("[MediaPlayer " + debugLabel + "] javax.sound: line unavailable after "
                                + AUDIO_LINE_OPEN_RETRIES + " attempts: " + e.getMessage());
                        return;
                    }
                    LoggingManager.warn("[MediaPlayer " + debugLabel + "] javax.sound: line unavailable, retry "
                            + attempt + "/" + AUDIO_LINE_OPEN_RETRIES);
                    try {
                        Thread.sleep(AUDIO_LINE_RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            long waitDeadline = System.nanoTime() + 5_000_000_000L;
            while (startWallNanos == CLOCK_NOT_STARTED
                    && !terminated.get() && !stopFlag.get()
                    && System.nanoTime() < waitDeadline) {
                LockSupport.parkNanos(10_000_000L);
            }
            if (terminated.get() || stopFlag.get()) return;
            line.start();
            currentAudioLine = line;

            byte[] chunk = new byte[AUDIO_CHUNK_BYTES];
            while (!terminated.get() && !stopFlag.get()) {
                int n = MediaUtils.readFull(in, chunk, AUDIO_CHUNK_BYTES);
                if (n <= 0) break;
                MediaBufferEffects.applyVolumeS16LE(chunk, n, currentVolume);
                int written = 0;
                while (written < n && !terminated.get() && !stopFlag.get()) {
                    int w = line.write(chunk, written, n - written);
                    if (w <= 0) break;
                    written += w;
                }
            }
        } catch (IOException e) {
            if (!terminated.get() && !stopFlag.get()) {
                LoggingManager.warn("[MediaPlayer " + debugLabel + "] Audio read error: " + e.getMessage());
            }
        } catch (Exception e) {
            if (!terminated.get() && !stopFlag.get()) {
                LoggingManager.warn("[MediaPlayer " + debugLabel + "] Audio pipeline error: " + e.getMessage());
            }
        } finally {
            if (line != null) {
                currentAudioLine = null;
                try {
                    line.flush();
                } catch (Exception ignored) {
                }
                try {
                    line.stop();
                } catch (Exception ignored) {
                }
                try {
                    line.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handleStreamEnd(String stderr, boolean normalEos, boolean isVideo) {
        if (terminated.get()) return;

        boolean is403or404 = stderr.contains("403") || stderr.contains("Forbidden")
                || stderr.contains("404") || stderr.contains("Not Found");
        boolean isTransient = MediaUtils.isTransientError(stderr);

        if (is403or404 && fetchRetries < MAX_FETCH_RETRIES) {
            scheduleRetry(true);
            return;
        }

        if (isTransient && !is403or404 && fetchRetries < MAX_FETCH_RETRIES) {
            scheduleRetry(false);
            return;
        }

        if (normalEos && liveStream && fetchRetries < MAX_FETCH_RETRIES) {
            LoggingManager.warn("[MediaPlayer " + debugLabel + "] live EOS – retrying");
            scheduleRetry(true);
            return;
        }

        if (normalEos && !liveStream) {
            if (restartPending.compareAndSet(false, true)) {
                safeExecute(() -> {
                    try {
                        YtStream vs = currentVideoStream;
                        YtStream as = currentAudioStream;
                        if (!terminated.get() && !displayScreen.getPaused() && vs != null && as != null) {
                            seekOffsetNanos = 0;
                            startStreams(vs, as, 0);
                            displayScreen.afterSeek();
                        }
                    } finally {
                        restartPending.set(false);
                    }
                });
            }
            return;
        }

        if (!stderr.isEmpty()) {
            LoggingManager.error("[MediaPlayer " + debugLabel + "] unrecoverable stream error: "
                    + MediaUtils.truncate(stderr));
        }
        displayScreen.errored = true;
    }

    private void scheduleRetry(boolean invalidateCache) {
        int attempt = fetchRetries++;
        long delayMs = attempt < RETRY_BACKOFF_MS.length ? RETRY_BACKOFF_MS[attempt] : RETRY_BACKOFF_MS[RETRY_BACKOFF_MS.length - 1];
        String reason = invalidateCache ? "cache invalidated" : "transient error";
        LoggingManager.warn("[MediaPlayer " + debugLabel + "] " + reason + " — retry "
                + fetchRetries + "/" + MAX_FETCH_RETRIES + " in " + delayMs + "ms");
        if (invalidateCache) {
            YtDlp.invalidateCache(youtubeUrl);
        }
        initialized = false;
        INIT_EXECUTOR.submit(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!terminated.get()) initialize();
        });
    }

    private ByteBuffer ensurePreparedBufferCapacity(int size) {
        ByteBuffer buffer = preparedBuffer;
        if (buffer == null || preparedBufferSize < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
            preparedBuffer = buffer;
            preparedBufferSize = size;
        }
        buffer.clear();
        buffer.limit(size);
        return buffer;
    }

    private void prepareBuffer() {
        try {
            int targetW = displayScreen.textureWidth, targetH = displayScreen.textureHeight;
            if (targetW == 0 || targetH == 0) return;

            synchronized (frameLock) {
                if (currentFrameBuffer == null) return;

                int sourceW = currentFrameWidth;
                int sourceH = currentFrameHeight;
                int outputSize = targetW * targetH * 4;
                ByteBuffer source = currentFrameBuffer.duplicate().order(ByteOrder.nativeOrder());
                source.rewind();
                ByteBuffer output = ensurePreparedBufferCapacity(outputSize);

                if (sourceW == targetW && sourceH == targetH) {
                    output.put(source);
                    output.flip();
                } else {
                    output.position(0).limit(outputSize);
                    ConverterUtil.scaleRGBA(source, sourceW, sourceH, output, targetW, targetH);
                    output.position(0).limit(outputSize);
                }

                MediaBufferEffects.applyBrightness(output, brightness);
                preparedW = targetW;
                preparedH = targetH;
                frameReady = true;
            }

            Minecraft.getInstance().execute(displayScreen::fitTexture);
        } finally {
            frameTaskQueued.set(false);
        }
    }

    private void doPlay() {
        if (!initialized || terminated.get()) return;
        if (playing) return;
        YtStream vs = currentVideoStream, as = currentAudioStream;
        if (vs == null || as == null) return;
        playing = true;
        startStreams(vs, as, seekOffsetNanos);
    }

    private void doPause() {
        if (!playing) return;
        SourceDataLine ln = currentAudioLine;
        long pauseOffset = -1;
        if (ln != null) {
            try {
                long playedFrames = ln.getLongFramePosition();
                pauseOffset = seekOffsetNanos
                        + playedFrames * 1_000_000_000L / AUDIO_SAMPLE_RATE;
            } catch (Exception ignored) {
            }
        }
        seekOffsetNanos = (pauseOffset >= 0) ? pauseOffset : getCurrentTime();
        playing = false;
        stopStreams();
    }

    private void doStop() {
        initialized = false;
        frameTaskQueued.set(false);
        synchronized (frameLock) {
            frameReady = false;
            preparedBuffer = null;
            preparedBufferSize = 0;
            currentFrameBuffer = null;
        }
        stopWatchdog();
        stopStatsReporter();
        stopStreams();
        currentVideoStream = null;
        currentAudioStream = null;
    }

    private void doSeek(long nanos, boolean fire) {
        if (!initialized || !seekable) return;
        YtStream vs = currentVideoStream, as = currentAudioStream;
        if (vs == null || as == null) return;

        boolean wasPlaying = playing;
        synchronized (frameLock) {
            frameReady = false;
            preparedBuffer = null;
            preparedBufferSize = 0;
        }
        Minecraft.getInstance().execute(displayScreen::reloadTexture);

        seekOffsetNanos = nanos;
        if (wasPlaying) {
            startStreams(vs, as, nanos);
        }
        if (fire) displayScreen.afterSeek();
    }

    private void changeQuality(String desired) {
        if (!initialized || availableVideoStreams == null) return;
        YtStream currentVideo = currentVideoStream, currentAudio = currentAudioStream;
        if (currentVideo == null || currentAudio == null) return;

        int target = MediaStreamSelector.parseQualityValue(desired, -1);
        if (target < 0 || target == lastQuality) return;

        Optional<YtStream> best = MediaStreamSelector.pickVideo(availableVideoStreams, target);
        if (best.isEmpty()) return;
        YtStream chosenVideo = best.get();
        if (chosenVideo.getUrl().equals(currentVideo.getUrl())) return;

        YtStream chosenAudio = currentAudio;
        if (availableAudioStreams != null) {
            Optional<YtStream> audioOpt = MediaStreamSelector.pickAudio(availableAudioStreams, lang, chosenVideo);
            if (audioOpt.isPresent()) chosenAudio = audioOpt.get();
        }

        long pos = liveStream ? 0L : getCurrentTime();
        Minecraft.getInstance().execute(displayScreen::reloadTexture);
        currentVideoStream = chosenVideo;
        currentAudioStream = chosenAudio;
        lastQuality = MediaStreamSelector.parseQuality(chosenVideo);
        if (playing) {
            startStreams(chosenVideo, chosenAudio, pos);
        } else {
            seekOffsetNanos = pos;
        }
    }

    private void startWatchdog() {
        stopWatchdog();
        lastFrameReceivedNanos.set(System.nanoTime());
        ScheduledExecutorService wd = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MediaPlayer-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdogExecutor = wd;
        wd.scheduleAtFixedRate(() -> {
            try {
                if (terminated.get() || !playing) return;
                long lastFrame = lastFrameReceivedNanos.get();
                if (lastFrame == 0) return;
                long elapsed = System.nanoTime() - lastFrame;
                if (elapsed > WATCHDOG_TIMEOUT_NS) {
                    LoggingManager.warn("[MediaPlayer " + debugLabel + "] watchdog: no frames for "
                            + (elapsed / 1_000_000L) + "ms — restarting streams");
                    lastFrameReceivedNanos.set(System.nanoTime());
                    safeExecute(() -> {
                        if (terminated.get()) return;
                        YtStream vs = currentVideoStream, as = currentAudioStream;
                        if (vs != null && as != null) {
                            long pos = liveStream ? 0L : getCurrentTime();
                            startStreams(vs, as, pos);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }, WATCHDOG_CHECK_INTERVAL_MS, WATCHDOG_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopWatchdog() {
        ScheduledExecutorService wd = watchdogExecutor;
        if (wd != null) {
            wd.shutdownNow();
            watchdogExecutor = null;
        }
    }

    private void startStatsReporter() {
        if (statsExecutor != null) return;
        statsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MediaPlayer-stats");
            t.setDaemon(true);
            return t;
        });
        statsExecutor.scheduleAtFixedRate(
                this::reportStats,
                STATS_INTERVAL_MS, STATS_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void stopStatsReporter() {
        ScheduledExecutorService ex = statsExecutor;
        if (ex != null) {
            ex.shutdownNow();
            statsExecutor = null;
        }
    }

    private void reportStats() {
        try {
            long inN = samplesIn.getAndSet(0);
            long outN = framesToGpu.getAndSet(0);
            long dropN = framesDropped.getAndSet(0);
            double seconds = STATS_INTERVAL_MS / 1000.0;
            LoggingManager.info(String.format(
                    "[MP debug %s] decode=%.1ffps gpu=%.1ffps dropped=%.1f/s | pos=%dms live=%s",
                    debugLabel,
                    inN / seconds, outN / seconds, dropN / seconds,
                    getCurrentTime() / 1_000_000L, liveStream
            ));
        } catch (Throwable ignored) {
        }
    }

    private void safeExecute(Runnable action) {
        if (!terminated.get() && !controlExecutor.isShutdown()) {
            try {
                controlExecutor.submit(action);
            } catch (RejectedExecutionException ignored) {
            }
        }
    }
}
