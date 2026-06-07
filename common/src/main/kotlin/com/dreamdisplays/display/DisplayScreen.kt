package com.dreamdisplays.display

import com.dreamdisplays.Initializer
import com.dreamdisplays.client.ui.DisplayMenu
import com.dreamdisplays.client.ui.PipCorner
import com.dreamdisplays.client.ui.PipOverlay
import com.dreamdisplays.client.ui.PipOverlayManager
import com.dreamdisplays.client.ui.VideoPopoutWindow
import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.net.Packets
import com.dreamdisplays.utils.MinecraftScreenUtil
import com.dreamdisplays.ytdlp.YtDlp
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Represents a video display screen in the game world. */
class DisplayScreen(
    val uuid: UUID,
    val ownerUuid: UUID,
    private var x: Int,
    private var y: Int,
    private var z: Int,
    var facing: String,
    var width: Int,
    var height: Int,
    var isSync: Boolean,
) {
    private val mediaPlayerGeneration = AtomicLong()
    private val savedSettings = DisplaySettings.getSettings(uuid)

    var owner: Boolean = Minecraft.getInstance().player?.gameProfile?.id?.toString() == ownerUuid.toString()
    val isAdmin: Boolean get() = Initializer.isAdmin
    var isLocked: Boolean? = null
    var errored: Boolean = false
    val canEdit: Boolean get() = owner || isAdmin || isLocked != true
    var muted: Boolean = savedSettings.muted
    var texture: DynamicTexture? = null
    var textureId: Identifier? = null
    var renderType: RenderType? = null
    var textureWidth: Int = 0
    var textureHeight: Int = 0
    @Volatile var videoContentAspect: Double = 0.0

    var volume: Float = savedSettings.volume
        set(value) {
            field = value
            applyEffectiveVolume()
            DisplaySettings.updateSettings(uuid, value, quality, brightness, muted, paused)
        }
    var brightness: Float = savedSettings.brightness
        set(value) {
            field = value.coerceIn(0f, 2f)
            mediaPlayer?.setBrightness(field)
            DisplaySettings.updateSettings(uuid, volume, quality, field, muted, paused)
        }
    var quality: String = savedSettings.quality
        set(value) {
            field = value
            mediaPlayer?.setQuality(value)
            DisplaySettings.updateSettings(uuid, volume, value, brightness, muted, paused)
        }
    private var videoStarted: Boolean = false
    private var paused: Boolean = savedSettings.paused
    private var focusMuted: Boolean = false
    var renderDistance: Int = 64
    var savedTimeNanos: Long = 0
    @Volatile private var serverSyncReceivedAt: Long = 0L
    @Volatile private var lastSyncTargetNs: Long = -1L
    @Volatile private var lastSyncRecvWallNs: Long = 0L
    private var mediaPlayer: MediaPlayer? = null
    var videoUrl: String? = null
        private set
    private var clientUrlOverride: Boolean = false

    @Transient private var blockPos: BlockPos? = null
    var lang: String? = null
        private set
    private var popoutWindow: VideoPopoutWindow? = null
    private var pipOverlay: PipOverlay? = null

    val isVideoStarted: Boolean get() = mediaPlayer?.textureFilled() == true

    val isPopoutActive: Boolean
        get() = (popoutWindow?.isOpen == true) || (pipOverlay != null)

    val pos: BlockPos
        get() = blockPos ?: BlockPos(x, y, z).also { blockPos = it }

    val currentTimeNanos: Long get() = mediaPlayer?.getCurrentTime() ?: 0L

    val isLive: Boolean get() = mediaPlayer?.isLive() == true

    val mediaPlayerDurationNanos: Long get() = mediaPlayer?.getDuration() ?: 0L

    val qualityList: List<Int>
        get() = mediaPlayer?.getAvailableQualities() ?: emptyList()

    init {
        if (isSync) sendRequestSyncPacket()
    }

    /** Loads a new video from [videoUrl], preserving the current paused state. */
    fun loadVideo(videoUrl: String, lang: String) {
        if (!clientUrlOverride) DisplaySettings.setUrlOverride(uuid, null, null)
        loadVideoInternal(videoUrl, lang, true)
    }

    /** Loads and immediately starts [videoUrl] from the beginning, ignoring the saved paused state. */
    fun playVideoNow(videoUrl: String, lang: String) {
        paused = false
        savedTimeNanos = 0L
        loadVideoInternal(videoUrl, lang, false)
    }

    /** Overrides the server-assigned video with a client-side suggestion, sends a [Packets.SetVideo] packet, and starts playback. */
    fun playSuggestedVideo(videoUrl: String, lang: String) {
        clientUrlOverride = true
        DisplaySettings.setUrlOverride(uuid, videoUrl, lang)
        Initializer.sendPacket(Packets.SetVideo(uuid, videoUrl, lang))
        playVideoNow(videoUrl, lang)
    }

    /** Internal loader: stops any current player, creates a fresh [MediaPlayer], and wires up texture and popout sinks. */
    private fun loadVideoInternal(videoUrl: String, lang: String, preservePausedState: Boolean) {
        if (videoUrl == "") return

        YtDlp.prefetchFormats(videoUrl)

        val generation = mediaPlayerGeneration.incrementAndGet()
        val oldPlayer = mediaPlayer
        mediaPlayer = null
        videoStarted = false
        errored = false
        lastSyncTargetNs = -1L
        serverSyncReceivedAt = 0L
        oldPlayer?.stop()

        this.videoUrl = videoUrl
        this.lang = lang
        val shouldBePaused = preservePausedState && paused
        val newPlayer = MediaPlayer(videoUrl, lang, this)
        mediaPlayer = newPlayer
        val qualityInt = parseQualityOrDefault()
        textureWidth = ((width / height.toDouble()) * qualityInt).toInt()
        textureHeight = qualityInt

        popoutWindow?.let { win ->
            if (win.isOpen) newPlayer.setPopoutSink { buf, fw, fh -> win.updateFrame(buf, fw, fh, videoContentAspect) }
        }
        pipOverlay?.let { overlay ->
            newPlayer.setPopoutSink { buf, fw, fh -> overlay.updateFrame(buf, fw, fh, videoContentAspect) }
        }

        waitForMFInit(generation) {
            startVideo()
            if (shouldBePaused) {
                paused = true
                mediaPlayer?.pause()
            }
        }

        Minecraft.getInstance().execute { reloadTexture() }
    }

    /** Updates position, dimensions, and video URL from an incoming [Packets.Info] packet. */
    fun updateData(packet: Packets.Info) {
        x = packet.pos.x
        y = packet.pos.y
        z = packet.pos.z
        blockPos = null
        facing = packet.facingUtil.toString()
        width = packet.width
        height = packet.height
        isSync = packet.isSync
        isLocked = packet.isLocked
        owner = Minecraft.getInstance().player?.gameProfile?.id?.toString() == packet.ownerUuid.toString()

        if (videoUrl != packet.url || lang != packet.lang) {
            if (clientUrlOverride) return

            val ds = DisplaySettings.getSettings(uuid)
            val override = ds.urlOverride
            if (!override.isNullOrEmpty()) {
                clientUrlOverride = true
                val overrideLang = ds.langOverride ?: packet.lang
                paused = false
                loadVideo(override, overrideLang)
                return
            }

            paused = false
            loadVideo(packet.url, packet.lang)
            if (isSync) sendRequestSyncPacket()
        }
    }

    /** Sends a [Packets.RequestSync] packet to ask the server for the current playback state. */
    private fun sendRequestSyncPacket() {
        Initializer.sendPacket(Packets.RequestSync(uuid))
    }

    /** Applies a sync packet from the server: adjusts pause state and seeks if the owner made a real jump. */
    fun updateData(packet: Packets.Sync) {
        isSync = packet.isSync
        if (!isSync) return
        serverSyncReceivedAt = System.nanoTime()

        val nanos = System.nanoTime()
        val desiredPaused = packet.currentState

        waitForMFInit {
            if (!videoStarted) {
                paused = desiredPaused
                startVideo()
            }

            val lostTime = System.nanoTime() - nanos
            val targetTime = max(0L, packet.currentTime + lostTime)
            val drift = abs(targetTime - currentTimeNanos)
            val canSeek = canSeek()

            if (desiredPaused && !paused) setPaused(true)
            val mp = mediaPlayer
            val clockRunning = mp?.isClockRunning() == true
            val recvWallNs = System.nanoTime()
            val ownerSeeked = if (lastSyncTargetNs < 0) {
                true
            } else {
                val elapsed = recvWallNs - lastSyncRecvWallNs
                abs(targetTime - (lastSyncTargetNs + elapsed)) > SYNC_JUMP_THRESHOLD_NS
            }
            lastSyncTargetNs = targetTime
            lastSyncRecvWallNs = recvWallNs
            if (ownerSeeked && canSeek && clockRunning && drift > SYNC_SEEK_TOLERANCE_NS)
                seekVideoTo(targetTime)
            if (!desiredPaused && paused) setPaused(false)
        }
    }

    /** Recreates the GPU texture (e.g. after a resolution change). */
    fun reloadTexture() = createTexture()

    /** Pushes the current quality setting to the media player. */
    fun reloadQuality() {
        mediaPlayer?.setQuality(quality)
    }

    /** Returns true if [pos] falls within the screen's block bounding box. */
    fun isInScreen(pos: BlockPos): Boolean {
        var maxX = x
        val maxY = y + height - 1
        var maxZ = z
        when (facing) {
            "NORTH", "SOUTH" -> maxX += width - 1
            else -> maxZ += width - 1
        }
        return pos.x in x..maxX &&
                y <= pos.y && maxY >= pos.y &&
                z <= pos.z && maxZ >= pos.z
    }

    /** Returns the shortest Euclidean distance from [pos] to any block in the screen's bounding box. */
    fun getDistanceToScreen(pos: BlockPos): Double {
        var maxX = x
        val maxY = y + height - 1
        var maxZ = z
        when (facing) {
            "NORTH", "SOUTH" -> maxX += width - 1
            "EAST", "WEST" -> maxZ += width - 1
        }
        val clampedX = min(max(pos.x, x), maxX)
        val clampedY = min(max(pos.y, y), maxY)
        val clampedZ = min(max(pos.z, z), maxZ)
        return sqrt(pos.distSqr(BlockPos(clampedX, clampedY, clampedZ)))
    }

    /** Uploads the latest decoded frame to the GPU texture. Called on the render thread once per frame. */
    fun fitTexture() {
        val mp = mediaPlayer ?: return
        val tex = texture ?: return
        try {
            mp.updateFrame(tex.getTexture())
        } catch (e: Exception) {
            logger.warn("$uuid fitTexture failed: ${e.message ?: e::class.java.name}")
        }
        popoutWindow?.renderFrame()
    }

    /** Passes [volume] directly to the media player without persisting to settings. */
    fun setVideoVolume(volume: Float) {
        mediaPlayer?.setVolume(volume)
    }

    /**
     * Applies the effective volume to the media player, which is 0 if either [muted] or [focusMuted] is true,
     * otherwise the user's set [volume].
     */
    private fun applyEffectiveVolume() {
        setVideoVolume(if (muted || focusMuted) 0f else volume)
    }

    /** Opens or focuses the `GLFW` window mode. Closes PiP if active. */
    fun activateWindowMode() {
        if (!VideoPopoutWindow.isAvailable) return
        val mp = mediaPlayer ?: return
        pipOverlay?.startClose()
        pipOverlay = null
        val win = popoutWindow
        if (win != null && win.isOpen) {
            win.open(textureWidth.takeIf { it > 0 } ?: 1280, textureHeight.takeIf { it > 0 } ?: 720)
            return
        }
        try {
            val w = textureWidth.takeIf { it > 0 } ?: 1280
            val h = textureHeight.takeIf { it > 0 } ?: 720
            val newWin = win ?: VideoPopoutWindow {
                mediaPlayer?.setPopoutSink(null)
            }.also { popoutWindow = it }
            mp.setPopoutSink { buf, fw, fh -> newWin.updateFrame(buf, fw, fh, videoContentAspect) }
            newWin.open(w, h)
        } catch (e: Exception) {
            logger.warn("Could not open window: ${e.message}.")
        }
    }

    /** Shows the in-game PiP overlay at [corner]. Closes window mode if active. */
    fun activatePipMode(corner: PipCorner = PipCorner.BOTTOM_RIGHT) {
        val mp = mediaPlayer ?: return
        popoutWindow?.let { win -> if (win.isOpen) { mp.setPopoutSink(null); win.close() } }
        pipOverlay?.startClose()
        pipOverlay = null
        val overlay = PipOverlay(this, corner)
        if (PipOverlayManager.add(overlay)) {
            pipOverlay = overlay
            mp.setPopoutSink { buf, fw, fh -> overlay.updateFrame(buf, fw, fh, videoContentAspect) }
        } else {
            logger.warn("No PiP corners available (max 4).")
        }
    }

    /** Closes whichever popout mode is active. */
    fun deactivatePopout() {
        mediaPlayer?.setPopoutSink(null)
        popoutWindow?.let { if (it.isOpen) it.close() }
        pipOverlay?.startClose()
        pipOverlay = null
    }

    /** Applies volume, brightness, and paused state to the media player, then seeks to the saved position. */
    fun startVideo() {
        val mp = mediaPlayer ?: return
        videoStarted = true
        applyEffectiveVolume()
        mp.setBrightness(brightness)
        if (paused) mp.pause() else {
            mp.play()
            paused = false
        }
        restoreSavedTime()
        bootstrapServerSyncIfNeeded()
    }

    /**
     * For sync displays, the server's clock only exists once *someone* (the owner) has sent a
     * Sync packet. If a fresh server / no-one's-been-owner-yet situation leaves playStates empty,
     * RequestSync returns nothing and clients sit at 0 forever. So the owner sends a Sync ~1.5s
     * after startVideo, but only if no server Sync arrived in that window (otherwise we'd
     * overwrite the server's ticking clock with our local time=0 on every reconnect).
     */
    private fun bootstrapServerSyncIfNeeded() {
        if (!owner || !isSync) return
        val gen = mediaPlayerGeneration.get()
        com.dreamdisplays.player.util.daemon({
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return@daemon }
            if (gen != mediaPlayerGeneration.get()) return@daemon
            if (serverSyncReceivedAt > 0L) return@daemon
            if (!owner || !isSync) return@daemon
            sendSync()
        }, "MediaPlayer-bootstrap-sync").start()
    }

    val isPaused: Boolean get() = paused

    /** Pauses or resumes the media player; if the video hasn't started yet, defers until initialization completes. */
    fun setPaused(paused: Boolean) {
        if (!videoStarted) {
            this.paused = paused
            waitForMFInit { startVideo() }
            return
        }
        if (this.paused == paused) return
        this.paused = paused
        if (paused) mediaPlayer?.pause() else mediaPlayer?.play()
        DisplaySettings.updateSettings(uuid, volume, quality, brightness, muted, paused)
        if (owner && isSync) sendSync()
    }

    /** Seeks 5 seconds forward. */
    fun seekForward() = seekVideoRelative(5.0)

    /** Seeks 5 seconds backward. */
    fun seekBackward() = seekVideoRelative(-5.0)

    /** Seeks [seconds] seconds relative to the current playback position (negative = backward). */
    fun seekVideoRelative(seconds: Double) {
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekRelative(seconds)
    }

    /** Seeks to an absolute position [nanos] without firing the sync event (used for incoming sync packets). */
    fun seekVideoTo(nanos: Long) {
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekTo(nanos, false)
    }

    /** Seeks to [ms] milliseconds and fires the sync event so other clients follow. */
    fun seekToMillis(ms: Long) {
        val mp = mediaPlayer ?: return
        // fire=true so events.onSeek -> afterSeek -> sendSync propagates the seek to other
        // clients via the server. (seekVideoTo is the inbound-sync path and passes false.)
        if (mp.canSeek()) mp.seekTo(ms * 1_000_000L, true)
    }

    /** Stops the media player, releases GPU texture, closes any popout, and closes the display menu if open. */
    fun unregister() {
        mediaPlayerGeneration.incrementAndGet()
        videoStarted = false
        val currentPlayer = mediaPlayer
        mediaPlayer = null
        currentPlayer?.setPopoutSink(null)
        popoutWindow?.let { if (it.isOpen) it.close() }
        PipOverlayManager.remove(this)
        pipOverlay = null
        currentPlayer?.stop()

        val mc = Minecraft.getInstance()
        textureId?.let { id ->
            mc.execute {
                try {
                    mc.textureManager.release(id)
                } catch (_: Exception) {
                }
            }
        }

        val screen = MinecraftScreenUtil.currentScreen(mc)
        if (screen is DisplayMenu && screen.displayScreen === this) screen.onClose()
    }

    /** Mutes or unmutes the screen; no-op if already in the requested state. */
    fun mute(status: Boolean) {
        if (muted == status) return
        muted = status
        applyEffectiveVolume()
        DisplaySettings.updateSettings(uuid, volume, quality, brightness, muted, paused)
    }

    /** Applies temporary focus mute without changing the user's persisted mute setting. */
    fun setFocusMuted(status: Boolean) {
        if (focusMuted == status) return
        focusMuted = status
        applyEffectiveVolume()
    }

    /** Allocates (or reallocates) the [DynamicTexture] and [RenderType] for the current quality setting. */
    fun createTexture() {
        val qualityInt = parseQualityOrDefault()
        textureWidth = ((width / height.toDouble()) * qualityInt).toInt()
        textureHeight = qualityInt

        texture?.let { t ->
            t.close()
            textureId?.let { Minecraft.getInstance().textureManager.release(it) }
        }
        texture = DynamicTexture({ UUID.randomUUID().toString() }, NativeImage(NativeImage.Format.RGBA, textureWidth, textureHeight, false))
        textureId = Identifier.fromNamespaceAndPath(
            Initializer.MOD_ID,
            "screen-main-texture-$uuid-${UUID.randomUUID()}"
        )
        Minecraft.getInstance().textureManager.register(textureId!!, texture!!)
        renderType = createRenderType(textureId!!)
    }

    /** Sends the current playback state to the server as a [Packets.Sync] packet. */
    fun sendSync() {
        val mp = mediaPlayer ?: return
        Initializer.sendPacket(Packets.Sync(uuid, isSync, paused, mp.getCurrentTime(), mp.getDuration()))
    }

    /** Seeks to the saved playback position after reconnection; skipped for sync displays. */
    fun restoreSavedTime() {
        if (isSync) return
        val mp = mediaPlayer ?: return
        if (savedTimeNanos > 0) mp.seekTo(savedTimeNanos, false)
    }

    /** Returns true if the media player is ready and the stream supports seeking. */
    fun canSeek(): Boolean = mediaPlayer?.canSeek() == true

    /** Runs [action] once the current media player is initialized; guards against stale generations. */
    fun waitForMFInit(action: () -> Unit) = waitForMFInit(mediaPlayerGeneration.get(), action)

    /** Runs [action] when the player is initialized, only if [expectedGeneration] still matches (i.e. video hasn't changed). */
    private fun waitForMFInit(expectedGeneration: Long, action: () -> Unit) {
        val mp = mediaPlayer ?: return
        mp.whenInitialized {
            if (expectedGeneration != mediaPlayerGeneration.get()) return@whenInitialized
            if (mp !== mediaPlayer) return@whenInitialized
            if (errored) return@whenInitialized
            action()
        }
    }

    /** Parses the quality string (e.g. "720p") to an integer; falls back to [DEFAULT_QUALITY] if unparseable. */
    private fun parseQualityOrDefault(): Int = try {
        val parsed = quality.replace("p", "").toInt()
        if (parsed > 0) parsed else DEFAULT_QUALITY
    } catch (_: NumberFormatException) {
        logger.warn("Invalid quality value '$quality' for display $uuid, using ${DEFAULT_QUALITY}p.")
        DEFAULT_QUALITY
    }

    /** Called every game tick to update distance-based volume attenuation from [pos]. */
    fun tick(pos: BlockPos) {
        val maxRadius = if (isPopoutActive) Double.MAX_VALUE else Initializer.config.defaultDistance.toDouble()
        mediaPlayer?.tick(pos, maxRadius)
    }

    /** Called after a seek completes; broadcasts the new position to the server if this client is the owner. */
    fun afterSeek() {
        if (owner && isSync) sendSync()
    }

    companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayScreen")
        private const val DEFAULT_QUALITY = 720
        private const val SYNC_SEEK_TOLERANCE_NS = 750_000_000L
        private const val SYNC_JUMP_THRESHOLD_NS = 1_500_000_000L

        /** Creates a custom [RenderType] that samples texture [id] through the solid-block pipeline. */
        private fun createRenderType(id: Identifier): RenderType = RenderType.create(
            "dream-displays",
            RenderSetup.builder(RenderPipelines.SOLID_BLOCK)
                .withTexture("Sampler0", id)
                .affectsCrumbling()
                .useLightmap()
                .createRenderSetup()
        )
    }
}
