package com.dreamdisplays.displays

import com.dreamdisplays.Initializer
import com.dreamdisplays.api.DisplayFacing
import com.dreamdisplays.displays.store.ClientSettingsStore
import com.dreamdisplays.client.ui.DisplayMenu
import com.dreamdisplays.client.ui.PipCorner
import com.dreamdisplays.managers.DisplayPopoutManager
import com.dreamdisplays.managers.ClientStateManager
import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.render.DisplayGeometry
import com.dreamdisplays.render.DisplayTextureResource
import com.dreamdisplays.render.UploadPixelFormat
import com.dreamdisplays.protocol.DisplayInfo
import com.dreamdisplays.protocol.DisplaySync
import com.dreamdisplays.protocol.RequestSync
import com.dreamdisplays.protocol.SetVideo
import com.dreamdisplays.utils.FacingUtil
import com.dreamdisplays.utils.MinecraftScreenUtil
import com.dreamdisplays.media.api.DreamMediaException
import com.dreamdisplays.media.api.VideoQuality
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*

/** Represents a video display screen in the game world. */
class DisplayScreen(
    val uuid: UUID,
    val ownerUuid: UUID,
    private var x: Int,
    private var y: Int,
    private var z: Int,
    var facing: DisplayFacing,
    var width: Int,
    var height: Int,
    var isSync: Boolean,
) {
    private val savedSettings = ClientSettingsStore.getSettings(uuid)

    var owner: Boolean = Minecraft.getInstance().player?.gameProfile?.id?.toString() == ownerUuid.toString()
    val isAdmin: Boolean get() = ClientStateManager.isAdmin
    var isLocked: Boolean? = null
    @Volatile var mediaError: DreamMediaException? = null
    val errored: Boolean get() = mediaError != null
    val canEdit: Boolean get() = owner || isAdmin || isLocked != true
    var muted: Boolean = savedSettings.muted

    private val textureResource = DisplayTextureResource(uuid)
    val texture: DynamicTexture? get() = textureResource.texture
    val textureId: Identifier? get() = textureResource.textureId
    val renderType: RenderType? get() = textureResource.renderType

    /** True once either texture flavor (RGBA or YUV planes) is allocated and the screen can be drawn. */
    val hasTexture: Boolean get() = textureResource.hasTexture

    /** True when the GPU-side YUV path backs this display (brightness is applied in the shader). */
    val isYuvTexture: Boolean get() = textureResource.isYuv

    /** [RenderType] for the loading / error color quads (differs from [renderType] in YUV mode). */
    val fallbackRenderType: RenderType? get() = textureResource.fallbackRenderType
    val textureWidth: Int get() = textureResource.width
    val textureHeight: Int get() = textureResource.height
    @Volatile var videoContentAspect: Double = 0.0

    var volume: Float = savedSettings.volume
        set(value) {
            field = value
            applyEffectiveVolume()
            ClientSettingsStore.updateSettings(uuid, value, quality, brightness, muted, paused)
        }
    var brightness: Float = savedSettings.brightness
        set(value) {
            field = value.coerceIn(0f, 2f)
            mediaPlayer?.setBrightness(field)
            ClientSettingsStore.updateSettings(uuid, volume, quality, field, muted, paused)
        }
    var quality: VideoQuality = VideoQuality.parse(savedSettings.quality)
        set(value) {
            field = value
            mediaPlayer?.setQuality(value)
            ClientSettingsStore.updateSettings(uuid, volume, value, brightness, muted, paused)
        }
    internal val videoStarted: Boolean get() = media.videoStarted
    internal var paused: Boolean = savedSettings.paused
    private var focusMuted: Boolean = false
    var renderDistance: Int = 64
    var savedTimeNanos: Long = 0
    internal val syncController = DisplaySyncController(this)
    private val media = DisplayMediaController(this)
    private val mediaPlayer: MediaPlayer? get() = media.player
    private val popoutManager = DisplayPopoutManager(this) {
        mediaPlayer?.setPopoutSink(null)
    }
    var videoUrl: String? = null
        private set
    private var clientUrlOverride: Boolean = false

    @Transient private var blockPos: BlockPos? = null
    var lang: String? = null
        private set

    val isVideoStarted: Boolean get() = mediaPlayer?.textureFilled() == true

    val isPopoutActive: Boolean
        get() = popoutManager.isActive

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
        if (!clientUrlOverride) ClientSettingsStore.setUrlOverride(uuid, null, null)
        loadVideoInternal(videoUrl, lang, true)
    }

    /** Loads and immediately starts [videoUrl] from the beginning, ignoring the saved paused state. */
    fun playVideoNow(videoUrl: String, lang: String) {
        paused = false
        savedTimeNanos = 0L
        loadVideoInternal(videoUrl, lang, false)
    }

    /** Overrides the server-assigned video with a client-side suggestion, sends a [SetVideo] packet, and starts playback. */
    fun playSuggestedVideo(videoUrl: String, lang: String) {
        clientUrlOverride = true
        ClientSettingsStore.setUrlOverride(uuid, videoUrl, lang)
        Initializer.sendPacket(SetVideo(uuid, videoUrl, lang))
        playVideoNow(videoUrl, lang)
    }

    /** Internal loader: delegates the player swap to the [media] controller. */
    private fun loadVideoInternal(videoUrl: String, lang: String, preservePausedState: Boolean) {
        media.load(videoUrl, lang, preservePausedState)
    }

    /** Records the new [videoUrl] and [lang] when the media controller swaps players. */
    internal fun onVideoSwapped(videoUrl: String, lang: String) {
        this.videoUrl = videoUrl
        this.lang = lang
    }

    /** Sizes the GPU texture buffers for the current dimensions and quality before the first frame. */
    internal fun prepareTextureDimensions() {
        textureResource.prepareDimensions(width, height, parseQualityOrDefault())
    }

    /** Re-attaches the popout sink chain to a freshly created [player]. */
    internal fun attachPopout(player: MediaPlayer) {
        popoutManager.attachTo(player) { videoContentAspect }
    }

    /** Attaches or clears the menu preview raw-frame sink on the current player. */
    fun setPreviewFrameSink(sink: ((ByteBuffer, Int, Int, UploadPixelFormat) -> Unit)?) {
        mediaPlayer?.setPreviewSink(sink)
    }

    /** Updates position, dimensions, and video URL from an incoming [DisplayInfo] packet. */
    fun updateData(packet: DisplayInfo) {
        x = packet.x
        y = packet.y
        z = packet.z
        blockPos = null
        facing = FacingUtil.fromPacket(packet.facing.toByte()).toDisplayFacing()
        width = packet.width
        height = packet.height
        isSync = packet.isSync
        isLocked = packet.isLocked
        owner = Minecraft.getInstance().player?.gameProfile?.id?.toString() == packet.ownerId.toString()

        if (videoUrl != packet.url || lang != packet.lang) {
            if (clientUrlOverride) return

            val ds = ClientSettingsStore.getSettings(uuid)
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

    /** Sends a [RequestSync] packet to ask the server for the current playback state. */
    private fun sendRequestSyncPacket() {
        Initializer.sendPacket(RequestSync(uuid))
    }

    /** Applies a sync packet from the server: adjusts pause state and seeks if the owner made a real jump. */
    fun updateData(packet: DisplaySync) {
        isSync = packet.isSync
        if (!isSync) return
        syncController.onSyncPacket(packet.currentTimeMs, packet.isPaused)
    }

    /** Forces the pause state and starts playback; used by the sync controller before the video has started. */
    internal fun beginPlaybackPaused(desiredPaused: Boolean) {
        paused = desiredPaused
        startVideo()
    }

    /** True if the media player's playback clock is currently advancing. */
    internal fun isClockRunning(): Boolean = mediaPlayer?.isClockRunning() == true

    /** The current media-player generation, used by the sync controller to detect stale video swaps. */
    internal val mediaGeneration: Long get() = media.generationNow

    /** Recreates the GPU texture (e.g. after a resolution change). */
    fun reloadTexture() = createTexture()

    /** Pushes the current quality setting to the media player. */
    fun reloadQuality() {
        mediaPlayer?.setQuality(quality)
    }

    /** Returns true if [pos] falls within the screen's block bounding box. */
    fun isInScreen(pos: BlockPos): Boolean =
        DisplayGeometry.isInBounds(pos, x, y, z, width, height, facing)

    /** Returns the shortest Euclidean distance from [pos] to any block in the screen's bounding box. */
    fun getDistanceToScreen(pos: BlockPos): Double =
        DisplayGeometry.distanceTo(pos, x, y, z, width, height, facing)

    /** Uploads the latest decoded frame to the GPU texture(s). Called on the render thread once per frame. */
    fun fitTexture() {
        val mp = mediaPlayer ?: return
        try {
            if (textureResource.isYuv) {
                val y = textureResource.yPlane ?: return
                val u = textureResource.uPlane ?: return
                val v = textureResource.vPlane ?: return
                mp.updateFramePlanar(y.getTexture(), u.getTexture(), v.getTexture())
            } else {
                val tex = textureResource.texture ?: return
                mp.updateFrame(tex.getTexture())
            }
        } catch (e: Exception) {
            logger.warn("$uuid fitTexture failed: ${e.message ?: e::class.java.name}")
        }
    }

    /**
     * Renders the current frame to the popout window.
     * Must be called after all Minecraft / mod rendering for the frame is complete so that any
     * GL-context switch (GLFW backend on macOS) does not corrupt in-flight command buffers.
     */
    fun renderPopout() {
        popoutManager.renderFrame()
    }

    /** Passes [volume] directly to the media player without persisting to settings. */
    fun setVideoVolume(volume: Float) {
        mediaPlayer?.setVolume(volume)
    }

    /**
     * Applies the effective volume to the media player, which is 0 if either [muted] or [focusMuted] is true,
     * otherwise the user's set [volume].
     */
    internal fun applyEffectiveVolume() {
        setVideoVolume(if (muted || focusMuted) 0f else volume)
    }

    /** Opens or focuses the `GLFW` window mode. Closes PiP if active. */
    fun activateWindowMode() {
        val mp = mediaPlayer ?: return
        popoutManager.activateWindowMode(mp, textureWidth, textureHeight) { videoContentAspect }
    }

    /** Shows the in-game PiP overlay at [corner]. Closes window mode if active. */
    fun activatePipMode(corner: PipCorner = PipCorner.BOTTOM_RIGHT) {
        val mp = mediaPlayer ?: return
        popoutManager.activatePipMode(mp, corner) { videoContentAspect }
    }

    /** Closes whichever popout mode is active. */
    fun deactivatePopout() {
        popoutManager.deactivate(mediaPlayer)
    }

    /** Applies volume, brightness, and paused state to the media player, then seeks to the saved position. */
    fun startVideo() = media.start()

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
        ClientSettingsStore.updateSettings(uuid, volume, quality, brightness, muted, paused)
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
        val currentPlayer = media.shutdown()
        popoutManager.unregister(currentPlayer)
        currentPlayer?.stop()

        textureResource.releaseAsync()

        val mc = Minecraft.getInstance()
        val screen = MinecraftScreenUtil.currentScreen(mc)
        if (screen is DisplayMenu && screen.displayScreen === this) screen.onClose()
    }

    /** Mutes or unmutes the screen; no-op if already in the requested state. */
    fun mute(status: Boolean) {
        if (muted == status) return
        muted = status
        applyEffectiveVolume()
        ClientSettingsStore.updateSettings(uuid, volume, quality, brightness, muted, paused)
    }

    /** Applies temporary focus mute without changing the user's persisted mute setting. */
    fun setFocusMuted(status: Boolean) {
        if (focusMuted == status) return
        focusMuted = status
        applyEffectiveVolume()
    }

    /** Allocates (or reallocates) the GPU texture and render type for the current quality setting. */
    fun createTexture() {
        textureResource.allocate(width, height, parseQualityOrDefault())
    }

    /** Sends the current playback state to the server as a [DisplaySync] packet. */
    fun sendSync() {
        val mp = mediaPlayer ?: return
        Initializer.sendPacket(DisplaySync(uuid, isSync, paused, mp.getCurrentTime(), mp.getDuration()))
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
    fun waitForMFInit(action: () -> Unit) = media.whenInitialized(action)

    /** Resolves the current quality to a target pixel height; [VideoQuality.Auto] falls back to [DEFAULT_QUALITY]. */
    private fun parseQualityOrDefault(): Int = quality.targetHeight ?: DEFAULT_QUALITY

    /** Called every game tick to update distance-based volume attenuation from [pos]. */
    fun tick(pos: BlockPos) {
        val maxRadius = if (isPopoutActive) Double.MAX_VALUE else ClientStateManager.config.defaultDistance.toDouble()
        mediaPlayer?.tick(pos, maxRadius)
    }

    /** Called after a seek completes; broadcasts the new position to the server if this client is the owner. */
    fun afterSeek() {
        if (owner && isSync) sendSync()
    }

    companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayScreen")
        private const val DEFAULT_QUALITY = 720
    }
}
