package com.dreamdisplays.display

import com.dreamdisplays.Initializer
import com.dreamdisplays.client.ui.DisplayMenu
import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.net.Packets
import com.dreamdisplays.ytdlp.YtDlp
import me.inotsleep.utils.logging.LoggingManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
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
    var errored: Boolean = false
    var muted: Boolean = savedSettings.muted
    var texture: DynamicTexture? = null
    var textureId: Identifier? = null
    var renderType: RenderType? = null
    var textureWidth: Int = 0
    var textureHeight: Int = 0

    var volume: Float = savedSettings.volume
        set(value) {
            field = value
            setVideoVolume(value)
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
    var renderDistance: Int = 64
    var savedTimeNanos: Long = 0
    private var mediaPlayer: MediaPlayer? = null
    var videoUrl: String? = null
        private set
    private var clientUrlOverride: Boolean = false

    @Transient
    private var blockPos: BlockPos? = null
    var lang: String? = null
        private set

    val isVideoStarted: Boolean get() = mediaPlayer?.textureFilled() == true

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

    fun loadVideo(videoUrl: String, lang: String) {
        if (!clientUrlOverride) DisplaySettings.setUrlOverride(uuid, null, null)
        loadVideoInternal(videoUrl, lang, true)
    }

    fun playVideoNow(videoUrl: String, lang: String) {
        paused = false
        savedTimeNanos = 0L
        loadVideoInternal(videoUrl, lang, false)
    }

    fun playSuggestedVideo(videoUrl: String, lang: String) {
        clientUrlOverride = true
        DisplaySettings.setUrlOverride(uuid, videoUrl, lang)
        Initializer.sendPacket(Packets.SetVideo(uuid, videoUrl, lang))
        playVideoNow(videoUrl, lang)
    }

    private fun loadVideoInternal(videoUrl: String, lang: String, preservePausedState: Boolean) {
        if (videoUrl == "") return

        YtDlp.prefetchFormats(videoUrl)

        val generation = mediaPlayerGeneration.incrementAndGet()
        val oldPlayer = mediaPlayer
        mediaPlayer = null
        videoStarted = false
        errored = false
        oldPlayer?.stop()

        this.videoUrl = videoUrl
        this.lang = lang
        val shouldBePaused = preservePausedState && paused
        mediaPlayer = MediaPlayer(videoUrl, lang, this)
        val qualityInt = parseQualityOrDefault()
        textureWidth = ((width / height.toDouble()) * qualityInt).toInt()
        textureHeight = qualityInt

        waitForMFInit(generation) {
            startVideo()
            if (shouldBePaused) {
                paused = true
                mediaPlayer?.pause()
            }
        }

        Minecraft.getInstance().execute { reloadTexture() }
    }

    fun updateData(packet: Packets.Info) {
        x = packet.pos.x
        y = packet.pos.y
        z = packet.pos.z
        blockPos = null
        facing = packet.facingUtil.toString()
        width = packet.width
        height = packet.height
        isSync = packet.isSync
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

    private fun sendRequestSyncPacket() {
        Initializer.sendPacket(Packets.RequestSync(uuid))
    }

    fun updateData(packet: Packets.Sync) {
        isSync = packet.isSync
        if (!isSync) return

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
            if (canSeek && clockRunning && drift > SYNC_SEEK_TOLERANCE_NS) seekVideoTo(targetTime)
            if (!desiredPaused && paused) setPaused(false)
        }
    }

    fun reloadTexture() = createTexture()

    fun reloadQuality() {
        mediaPlayer?.setQuality(quality)
    }

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

    fun fitTexture() {
        val mp = mediaPlayer ?: return
        val tex = texture ?: return
        try {
            mp.updateFrame(tex.getTexture())
        } catch (_: Exception) {
        }
    }

    fun setVideoVolume(volume: Float) {
        mediaPlayer?.setVolume(volume)
    }

    fun startVideo() {
        val mp = mediaPlayer ?: return
        videoStarted = true
        setVideoVolume(if (muted) 0f else volume)
        mp.setBrightness(brightness)
        if (paused) mp.pause() else {
            mp.play()
            paused = false
        }
        restoreSavedTime()
    }

    val isPaused: Boolean get() = paused

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

    fun seekForward() = seekVideoRelative(5.0)
    fun seekBackward() = seekVideoRelative(-5.0)

    fun seekVideoRelative(seconds: Double) {
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekRelative(seconds)
    }

    fun seekVideoTo(nanos: Long) {
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekTo(nanos, false)
    }

    fun seekToMillis(ms: Long) {
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekTo(ms * 1_000_000L, false)
    }

    fun unregister() {
        mediaPlayerGeneration.incrementAndGet()
        videoStarted = false
        val currentPlayer = mediaPlayer
        mediaPlayer = null
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

        val screen = mc.screen
        if (screen is DisplayMenu && screen.displayScreen === this) screen.onClose()
    }

    fun mute(status: Boolean) {
        if (muted == status) return
        muted = status
        setVideoVolume(if (!status) volume else 0f)
        DisplaySettings.updateSettings(uuid, volume, quality, brightness, muted, paused)
    }


    fun createTexture() {
        val qualityInt = parseQualityOrDefault()
        textureWidth = ((width / height.toDouble()) * qualityInt).toInt()
        textureHeight = qualityInt

        texture?.let { t ->
            t.close()
            textureId?.let { Minecraft.getInstance().textureManager.release(it) }
        }
        texture = DynamicTexture(UUID.randomUUID().toString(), textureWidth, textureHeight, true)
        textureId = Identifier.fromNamespaceAndPath(
            Initializer.MOD_ID,
            "screen-main-texture-$uuid-${UUID.randomUUID()}"
        )
        Minecraft.getInstance().textureManager.register(textureId!!, texture!!)
        renderType = createRenderType(textureId!!)
    }

    fun sendSync() {
        val mp = mediaPlayer ?: return
        Initializer.sendPacket(Packets.Sync(uuid, isSync, paused, mp.getCurrentTime(), mp.getDuration()))
    }

    fun restoreSavedTime() {
        val mp = mediaPlayer ?: return
        if (savedTimeNanos > 0 && mp.isInitialized() && mp.canSeek()) {
            mp.seekTo(savedTimeNanos, false)
        }
    }

    fun canSeek(): Boolean = mediaPlayer?.canSeek() == true

    fun waitForMFInit(action: () -> Unit) = waitForMFInit(mediaPlayerGeneration.get(), action)

    private fun waitForMFInit(expectedGeneration: Long, action: () -> Unit) {
        val mp = mediaPlayer ?: return
        mp.whenInitialized {
            if (expectedGeneration != mediaPlayerGeneration.get()) return@whenInitialized
            if (mp !== mediaPlayer) return@whenInitialized
            if (errored) return@whenInitialized
            action()
        }
    }

    private fun parseQualityOrDefault(): Int = try {
        val parsed = quality.replace("p", "").toInt()
        if (parsed > 0) parsed else DEFAULT_QUALITY
    } catch (_: NumberFormatException) {
        LoggingManager.warn("[DisplayScreen] Invalid quality value '$quality' for display $uuid, using ${DEFAULT_QUALITY}p.")
        DEFAULT_QUALITY
    }

    fun tick(pos: BlockPos) {
        mediaPlayer?.tick(pos, Initializer.config.defaultDistance.toDouble())
    }

    fun afterSeek() {
        if (owner && isSync) sendSync()
    }

    companion object {
        private const val DEFAULT_QUALITY = 720
        private const val SYNC_SEEK_TOLERANCE_NS = 750_000_000L

        private fun createRenderType(id: Identifier): RenderType = RenderType.create(
            "dream-displays",
            RenderSetup.builder(RenderPipelines.SOLID_BLOCK)
                .withTexture("Sampler0", id)
                .bufferSize(RenderType.BIG_BUFFER_SIZE)
                .affectsCrumbling()
                .useLightmap()
                .createRenderSetup()
        )
    }
}
