package com.dreamdisplays.platform.client.displays

//? if >=1.21.11 {
//?} else
/*import net.minecraft.client.renderer.RenderType*/
//? if >=1.21.11 {
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.api.display.model.ContentRotation
import com.dreamdisplays.api.display.model.DisplayFacing
import com.dreamdisplays.api.media.DreamMediaException
import com.dreamdisplays.api.media.VideoQuality
import com.dreamdisplays.api.media.audio.*
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.playback.*
import com.dreamdisplays.api.storage.ClientDisplaySettings
import com.dreamdisplays.api.watchparty.WatchPartySession
import com.dreamdisplays.core.protocol.*
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.audio.ListenerPoseTracker
import com.dreamdisplays.platform.client.audio.VoxelAcousticsProbe
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayScreen.Companion.DEFAULT_QUALITY
import com.dreamdisplays.platform.client.displays.DisplayScreen.Companion.ENV_PROBE_INTERVAL_TICKS
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.managers.DisplayPopoutManager
import com.dreamdisplays.platform.client.net.ProtocolRouter
import com.dreamdisplays.platform.client.render.*
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import com.dreamdisplays.platform.client.ui.DisplayMenu
import com.dreamdisplays.platform.client.ui.PipCorner
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import com.dreamdisplays.util.FacingUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.abs

/** Represents a video display screen in the game world. */
class DisplayScreen(
    /** Stable unique id of this display, shared with the server. */
    val uuid: UUID,

    /** Id of the player who created and owns this display. */
    val ownerUuid: UUID,

    /** Anchor block X coordinate. */
    private var x: Int,

    /** Anchor block Y coordinate. */
    private var y: Int,

    /** Anchor block Z coordinate. */
    private var z: Int,

    /** Direction the screen surface faces. */
    var facing: DisplayFacing,

    /** Screen width in blocks. */
    var width: Int,

    /** Screen height in blocks. */
    var height: Int,

    /** Current base playback mode (`LOCAL` / `SYNCED` / `BROADCAST`). */
    var mode: PlaybackMode,

    /** Hard quality cap in pixel height for Broadcast, or `0` for no cap. */
    var qualityCap: Int = 0,

    /** Content rotation; only used for floor/ceiling (`UP`/`DOWN`) screens. */
    var rotation: ContentRotation = ContentRotation.NONE,
) {
    /** Per-display client settings (volume, quality, mute, ...) loaded from disk. */
    private val savedSettings = ClientSettingsStore.getSettings(uuid, defaultVolume())

    /** True if the local player owns this display. */
    var owner: Boolean = Minecraft.getInstance().player?.gameProfile?.id?.toString() == ownerUuid.toString()

    /** True if the local player has admin (OP) permissions. */
    val isAdmin: Boolean get() = ClientStateManager.isAdmin

    /** Server-reported lock state, or `null` until the server reports it. */
    var isLocked: Boolean? = null

    /** The last media failure on this display, or `null` when healthy. */
    @Volatile
    var mediaError: DreamMediaException? = null

    /** True while a media error is active. */
    val errored: Boolean get() = mediaError != null

    /** True if the local player may edit this display (owner, admin, or not effectively locked). */
    val canEdit: Boolean get() = owner || isAdmin || !effectiveLocked

    /** Whether the user has muted this display. */
    var muted: Boolean = savedSettings.muted

    /**
     * Whether the 3D acoustics engine (directivity, occlusion, reverb, binaural) applies to this
     * display; false forces the legacy distance-gain-only path.
     */
    var acousticsEnabled: Boolean = savedSettings.acousticsEnabled

    /** Legacy mirror of [mode]; true only for [PlaybackMode.SYNCED]. */
    val isSync: Boolean get() = mode == PlaybackMode.SYNCED

    /** Live watch-party session over this display, or null when none is running. */
    @Volatile
    var watchParty: WatchPartySession? = null; internal set

    /** Whether the local player has marked themselves ready in the current session (UI toggle state). */
    @Volatile
    var localWatchPartyReady: Boolean = false; internal set

    /** The effective mode the player experiences — `WATCH_PARTY` while a session is live. */
    val effectiveMode: PlaybackMode get() = if (watchParty != null) PlaybackMode.WATCH_PARTY else mode

    /** Permission context for the local player acting on this display (mirrors the server's rules). */
    private fun ctx(): PlaybackContext = PlaybackContext(
        mode = effectiveMode,
        isOwner = owner,
        isAdmin = isAdmin,
        isLocked = isLocked == true,
        hasActiveParty = watchParty != null,
        isPartyHost = watchParty?.isHost == true,
    )

    /** True if the local player may play/pause here. Locked displays allow only owner / admin controls. */
    val canControlPlayback: Boolean get() = PlaybackPermissions.canPlayPause(ctx())

    /** True if the local player may seek here. */
    val canSeekHere: Boolean get() = PlaybackPermissions.canSeek(ctx())

    /** True if the (personal) quality may be changed — false for Broadcast's hard cap. */
    val canChangeQualityHere: Boolean get() = PlaybackPermissions.canChangeQuality(ctx())

    /** True if the local player may change the display's video here (suggestions / SetVideo). */
    val canSetVideoHere: Boolean get() = PlaybackPermissions.canSetVideo(ctx())

    /** True if the local player may open the popout here — false in Broadcast for everyone. */
    val canPopoutHere: Boolean get() = PlaybackPermissions.canPopout(ctx())

    /** True if the base lock may be toggled (impossible in Watch Party / Broadcast). */
    val canToggleLockHere: Boolean get() = PlaybackPermissions.canToggleLock(ctx())

    /** True if the base mode may be switched. */
    val canSetModeHere: Boolean get() = PlaybackPermissions.canSetMode(ctx())

    /** True if the local player may start a watch party here. */
    val canStartWatchPartyHere: Boolean get() = PlaybackPermissions.canStartWatchParty(ctx())

    /** True if the local player may close the active watch party. */
    val canCloseWatchPartyHere: Boolean get() = PlaybackPermissions.canCloseWatchParty(ctx())

    /** The lock the player actually sees: base lock, or forced on by Watch Party / Broadcast. */
    val effectiveLocked: Boolean get() = PlaybackPermissions.isEffectivelyLocked(effectiveMode, isLocked == true)

    /** Backing store for this display's GPU texture(s) and render types. */
    private val textureResource = DisplayTextureResource(uuid)

    /** The live RGBA texture, or `null` in YUV mode / before allocation. */
    val texture: DynamicTexture? get() = textureResource.texture

    /** Resource identifier of the live texture, or `null` before allocation. */
    val textureId: Identifier? get() = textureResource.textureId

    /** [RenderType] used to draw the live video frame, or `null` before allocation. */
    val renderType: RenderType? get() = textureResource.renderType

    /** True once either texture flavor (RGBA or YUV planes) is allocated and the screen can be drawn. */
    val hasTexture: Boolean get() = textureResource.hasTexture

    /** True when the GPU-side YUV path backs this display (brightness is applied in the shader). */
    val isYuvTexture: Boolean get() = textureResource.isYuv

    /** [RenderType] for the loading / error color quads (differs from [renderType] in YUV mode). */
    val fallbackRenderType: RenderType? get() = textureResource.fallbackRenderType

    // During a quality handoff the new decoder must target the pending (new-resolution) texture,
    // not the live one — otherwise its frames never match the staged texture and the display freezes.
    val textureWidth: Int get() = if (textureResource.hasPending) textureResource.pendingWidth else textureResource.width
    val textureHeight: Int get() = if (textureResource.hasPending) textureResource.pendingHeight else textureResource.height

    /** Aspect ratio of the decoded video content (width / height); `0.0` until the first frame. */
    @Volatile
    var videoContentAspect: Double = 0.0

    /** User-set volume (`0.0`..`1.0`); writes apply the effective volume and persist the setting. */
    var volume: Float = savedSettings.volume
        set(value) {
            field = value
            applyEffectiveVolume()
            ClientSettingsStore.updateSettings(uuid, value, quality, brightness, muted, paused)
            DisplayRegistry.recordScreen(this)
        }

    /** Display brightness (`0`..`2`); writes push to the player and persist the setting. */
    var brightness: Float = savedSettings.brightness
        set(value) {
            field = value.coerceIn(0f, 2f)
            mediaPlayer?.setBrightness(field)
            ClientSettingsStore.updateSettings(uuid, volume, quality, field, muted, paused)
            DisplayRegistry.recordScreen(this)
        }

    /** Requested video quality; writes push the effective quality to the player and persist the setting. */
    var quality: VideoQuality = VideoQuality.parse(savedSettings.quality)
        set(value) {
            field = value
            mediaPlayer?.setQuality(effectiveQuality(value))
            ClientSettingsStore.updateSettings(uuid, volume, value, brightness, muted, paused)
            DisplayRegistry.recordScreen(this)
        }

    /**
     * Requested audio track, identified by its resolved stream URL; writes push the choice to the
     * player, which respawns only the audio line. Not persisted — track URLs are re-resolved per
     * video, so there's nothing stable to save across sessions.
     */
    var audioTrack: String = ""
        set(value) {
            field = value
            mediaPlayer?.setAudioTrack(value)
        }

    /**
     * In Broadcast ([qualityCap] > 0) every client is pinned to the highest allowed quality (the
     * cap, e.g. 360p) regardless of the user's saved setting; otherwise the user's [requested]
     * quality is used unchanged.
     */
    private fun effectiveQuality(requested: VideoQuality = quality): VideoQuality {
        if (qualityCap <= 0) return requested
        return VideoQuality.Fixed(qualityCap)
    }

    /** True once the controller has applied the screen's initial state to the current player. */
    internal val videoStarted: Boolean get() = media.videoStarted

    /** Local paused state (user intent / server-followed). */
    internal var paused: Boolean = savedSettings.paused

    /** Temporary mute applied while the game window is unfocused; does not change [muted]. */
    private var focusMuted: Boolean = false

    /** Distance in blocks past which the display is unloaded; writes record the new value. */
    var renderDistance: Int = 96
        set(value) {
            field = value
            DisplayRegistry.recordScreen(this)
        }

    /** Last known playback position in nanoseconds, restored on reconnect. */
    var savedTimeNanos: Long = 0

    /** Follows the server-authoritative timeline (Synced / Broadcast / watch party). */
    internal val timelineFollower = TimelineFollower(this)

    /** Owns the media player lifecycle (creation, swaps, teardown). */
    private val media = DisplayMediaController(this)

    /** Emits the local player's watch-party control intents. */
    private val watchPartyController = WatchPartyController(this)

    /** Pushes decoded frames into the GPU texture(s) on the render thread. */
    private val frameUploader = DisplayFrameUploader(uuid)

    /** The active media player, or `null` between videos. */
    private val mediaPlayer: MediaPlayer? get() = media.player

    /** True, while this display is parked warm out of render distance: not rendered and not advancing, but
     *  its decoder and audio stay open, so walking back resumes instantly.
     *
     *  @see [goDormant]
     *  @see [wake]
     */
    @Volatile
    var isDormant: Boolean = false; private set

    /** [System.nanoTime] when the display entered warm park; used for TTL eviction. */
    private var dormantSinceNanos = 0L

    /** Manages the PiP / window / fullscreen popout for this display. */
    private val popoutManager = DisplayPopoutManager(this)

    /** The currently loaded video URL, or `null` when idle. */
    var videoUrl: String? = null; private set

    /** True while a client-side URL override is active (suppresses server URL changes). */
    private var clientUrlOverride: Boolean = false

    /** Cached [BlockPos] for the anchor, lazily rebuilt when [x]/[y]/[z] change. */
    @Transient
    private var blockPos: BlockPos? = null

    /**
     * True once at least one decoded frame has been uploaded to the live texture. Keeps the screen
     * showing its last frame (rather than the loading quad) across moments when the pipe has no ready
     * frame — most importantly during a quality handoff, while the new-resolution pipe spins up.
     * Reset by [createTexture] (full reallocation: new video, resize, backend restart).
     */
    @Transient
    @Volatile
    private var hasEverRendered = false

    /** [System.nanoTime] of the first uploaded frame, driving the appear fade-in. `0` = none yet. */
    @Transient
    @Volatile
    private var firstFrameNanos = 0L

    /** True while waiting for the server's first timeline before showing the video (Synced / Broadcast / WP). */
    @Transient
    @Volatile
    private var waitingForInitialTimeline = false

    /**
     * [System.nanoTime] [waitingForInitialTimeline] was last set; `0` while not waiting. Backs the
     * self-heal timeout below — a [TimelineFollower] packet that never lands (dropped, superseded by a
     * stale-generation guard, or simply never sent for this display) must not strand the picture behind
     * "Waiting for video..." forever once decode is actually healthy.
     */
    @Transient
    @Volatile
    private var waitingSinceNanos = 0L

    /**
     * Re-checks [waitingForInitialTimeline], self-clearing it once it has outlasted the periodic
     * server broadcast interval by a wide margin — see [waitingSinceNanos].
     */
    private fun stillWaitingForInitialTimeline(): Boolean {
        if (waitingForInitialTimeline && waitingSinceNanos != 0L &&
            System.nanoTime() - waitingSinceNanos > WAITING_FOR_TIMELINE_TIMEOUT_NANOS
        ) {
            markInitialTimelineReady()
        }
        return waitingForInitialTimeline
    }

    /** Audio track / language of the current video, or `null` when idle. */
    var lang: String? = null; private set

    /** True once the video is effectively playing: not awaiting the initial timeline and a frame has filled. */
    val isVideoStarted: Boolean get() = !stillWaitingForInitialTimeline() && (hasEverRendered || mediaPlayer?.textureFilled() == true)

    /** Marks that a frame has rendered, stamping the first-frame time so the appear fade-in can run. */
    private fun markRendered() {
        if (!hasEverRendered) firstFrameNanos = System.nanoTime()
        hasEverRendered = true
    }

    /**
     * Eased 0..1 fade applied to the video on its first appearance, so it ramps up from black instead
     * of snapping in. Returns 1 (no fade) before the first frame, once the ramp is over, and for a
     * seamless replay reappearance (which must not dim its already-good picture).
     */
    internal fun appearProgress(): Float {
        val start = firstFrameNanos
        if (start == 0L || mediaPlayer?.isResumingFromReplay() == true) return 1f
        val dt = System.nanoTime() - start
        if (dt >= APPEAR_FADE_NANOS) return 1f
        val t = (dt.toFloat() / APPEAR_FADE_NANOS).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t) // Smoothstep
    }

    /** True while a PiP or window popout is open for this display. */
    val isPopoutActive: Boolean; get() = popoutManager.isActive

    /** Anchor block position of the display (cached). */
    val pos: BlockPos; get() = blockPos ?: BlockPos(x, y, z).also { blockPos = it }

    // Resume position, not the raw clock: while a replay -> live bridge is mid-flight this reports the live
    // edge instead of the replay playhead, so unloading then (rapid leave / return) never regresses the
    // saved / captured position by the replay lead. Identical to the clock in normal playback.
    val currentTimeNanos: Long get() = mediaPlayer?.getResumePositionNanos() ?: 0L

    /** True when the current stream is a live broadcast (no seekable duration). */
    val isLive: Boolean get() = mediaPlayer?.isLive() == true

    /** Total duration of the current video in nanoseconds, or `0` if unknown / live. */
    val mediaPlayerDurationNanos: Long get() = mediaPlayer?.getDuration() ?: 0L

    /**
     * Raw (not yet redirect-resolved) URL of the current video's stream, for seek-bar scrub-preview
     * frame extraction. Cheap to read; null for live streams or before a stream has resolved.
     */
    val scrubPreviewRawUrl: String? get() = mediaPlayer?.capturedStreamRawUrl()

    /** Pixel heights of the qualities available for the current video. */
    val qualityList: List<Int>
        get() = mediaPlayer?.getAvailableQualities() ?: emptyList()

    /** Audio tracks available for the current video (more than one only when the provider exposes dubs). */
    val audioTrackList: List<MediaStream>
        get() = mediaPlayer?.getAvailableAudioTracks() ?: emptyList()

    /** Resolved URL of the audio track currently playing, or empty before a stream has resolved. */
    val currentAudioTrackUrl: String
        get() = mediaPlayer?.getCurrentAudioTrack() ?: ""

    init {
        // Ask the server for the current timeline / session; it replies only if it has one
        sendRequestSyncPacket()
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

    /**
     * Re-attempts the current video after a load failure. Purely local: re-resolves and restarts the
     * same URL (clearing [mediaError] via the controller), with no server packet and no URL-override
     * change — so a transient resolve failure never costs the display.
     */
    fun retryVideo() {
        val url = videoUrl ?: return
        loadVideoInternal(url, lang ?: "", preservePausedState = true)
    }

    /** Requests a server-authoritative video change from a picked suggestion. */
    fun playSuggestedVideo(videoUrl: String, lang: String): Boolean {
        if (!canSetVideoHere) return false
        clientUrlOverride = false
        ClientSettingsStore.setUrlOverride(uuid, null, null)
        Initializer.sendPacket(SetVideo(uuid, videoUrl, lang))
        return true
    }

    /** Internal loader: delegates the player swap to the [media] controller. */
    private fun loadVideoInternal(videoUrl: String, lang: String, preservePausedState: Boolean) {
        media.load(videoUrl, lang, preservePausedState)
    }

    /** Records the new [videoUrl] and [lang] when the media controller swaps players. */
    internal fun onVideoSwapped(videoUrl: String, lang: String) {
        this.videoUrl = videoUrl
        this.lang = lang
        waitingForInitialTimeline = requiresServerTimeline()
        waitingSinceNanos = if (waitingForInitialTimeline) System.nanoTime() else 0L
    }

    /** True while the screen is holding back the picture until the server's first timeline arrives. */
    internal val isWaitingForInitialTimeline: Boolean get() = stillWaitingForInitialTimeline()

    /** Clears the initial-timeline gate so the video may render. */
    internal fun markInitialTimelineReady() {
        waitingForInitialTimeline = false
        waitingSinceNanos = 0L
    }

    /** Primes the player to begin at [positionNanos] so the first frame lands on the synced position. */
    internal fun primeTimelineStart(positionNanos: Long) {
        mediaPlayer?.primeStartPosition(positionNanos.coerceAtLeast(0L))
    }

    /** Drops the rendered frame so the screen re-fades in after a timeline-driven seek. */
    internal fun clearRenderedFrameForTimeline() {
        hasEverRendered = false
        firstFrameNanos = 0L
        mediaPlayer?.clearFrame()
    }

    /** True when the current mode takes its timeline from the server (Synced / Broadcast / watch party). */
    private fun requiresServerTimeline(): Boolean =
        mode == PlaybackMode.SYNCED || mode == PlaybackMode.BROADCAST || watchParty != null

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
        mediaPlayer?.setPreviewSink(
            if (sink == null) null else { buf, w, h, fmt -> sink(buf, w, h, fmt.toUploadFormat()) },
        )
    }

    /** Lazily created menu-preview texture, kept alive across menu close/reopen (see [PreviewFrameTexture]). */
    @Transient
    private var previewFrameCache: PreviewFrameTexture? = null

    /** The display's persistent preview texture, created on first use and released in [unregister]. */
    internal fun previewFrameTexture(): PreviewFrameTexture =
        previewFrameCache ?: PreviewFrameTexture(uuid).also { previewFrameCache = it }

    /** Updates position, dimensions, and video URL from an incoming [DisplayInfo] packet. */
    fun updateData(packet: DisplayInfo) {
        x = packet.x
        y = packet.y
        z = packet.z
        blockPos = null
        facing = FacingUtil.fromPacket(packet.facing.toByte()).toDisplayFacing()
        rotation = ContentRotation.fromQuarterTurns(packet.rotation)
        width = packet.width
        height = packet.height

        val nextMode = if (packet.mode == PlaybackMode.LOCAL.wire && packet.isSync) {
            PlaybackMode.SYNCED
        } else {
            PlaybackMode.fromWire(packet.mode)
        }
        mode = nextMode

        qualityCap = packet.qualityCap
        isLocked = packet.isLocked
        owner = Minecraft.getInstance().player?.gameProfile?.id?.toString() == packet.ownerId.toString()

        if (videoUrl != packet.url || lang != packet.lang) {
            val previousUrl = videoUrl
            if (clientUrlOverride && canSetVideoHere) return
            if (clientUrlOverride) {
                clientUrlOverride = false
                ClientSettingsStore.setUrlOverride(uuid, null, null)
            }

            val ds = ClientSettingsStore.getSettings(uuid)
            val override = ds.urlOverride

            if (!override.isNullOrEmpty() && canSetVideoHere) {
                clientUrlOverride = true
                val overrideLang = ds.langOverride ?: packet.lang
                paused = false
                if (override != previousUrl) savedTimeNanos = 0L
                loadVideo(override, overrideLang)
                return
            } else if (!override.isNullOrEmpty()) {
                ClientSettingsStore.setUrlOverride(uuid, null, null)
            }

            paused = false
            if (packet.url != previousUrl) savedTimeNanos = 0L
            loadVideo(packet.url, packet.lang)
            sendRequestSyncPacket()
        }
    }

    /** Sends a [RequestSync] packet to ask the server for the current playback state. */
    private fun sendRequestSyncPacket() {
        Initializer.sendPacket(RequestSync(uuid))
    }


    /** Applies the authoritative server timeline: matches pause state and corrects drift. */
    fun updateData(packet: DisplaySync) {
        if (watchParty != null) return
        if (isLegacySync(packet) && usesV2Timeline()) return
        timelineFollower.apply(packet.currentTimeMs, packet.serverTimeMs, packet.isPaused, packet.loop)
    }

    /** Legacy sync packets have no v2 timeline metadata; on modes-capable servers they are stale v1 keepalives. */
    private fun isLegacySync(packet: DisplaySync): Boolean =
        packet.mode == PlaybackMode.LOCAL.wire && packet.serverTimeMs == 0L && !packet.loop

    /** True once sync should come from v2 server timelines rather than the frozen-v1 owner relay. */
    private fun usesV2Timeline(): Boolean =
        ProtocolRouter.v2Negotiated || ClientPacketManager.serverSnapshot.hasFeature(ServerFeature.MODES)

    /**
     * Applies a watch-party snapshot: tracks the session for UI / permissions, loads the host's video
     * when it changes, and (while `PLAYING` / `PAUSED`) follows the session timeline. An empty session id
     * means the party closed — the display reverts to its base mode.
     */
    fun updateWatchParty(packet: WatchPartyState) {
        if (packet.sessionId.isEmpty()) {
            watchParty = null
            localWatchPartyReady = false
            sendRequestSyncPacket() // Pull the base-mode timeline back
            return
        }
        if (watchParty?.sessionId != packet.sessionId) localWatchPartyReady = false
        val localId = Minecraft.getInstance().player?.gameProfile?.id
        val state = WatchPartySessionState.fromWire(packet.state)
        watchParty = WatchPartySession(
            sessionId = packet.sessionId,
            state = state,
            isHost = localId != null && localId == packet.hostId,
            hostName = packet.hostName,
            readyCount = packet.readyCount,
            nearbyCount = packet.nearbyCount,
            positionMs = packet.positionMs,
            // Both stamps are server-time, so this is immune to client / server wall-clock skew
            countdownRemainingMs = (packet.countdownStartEpochMs - packet.serverTimeMs)
                .takeIf { state == WatchPartySessionState.COUNTDOWN && it > 0 },
        )

        if (packet.url.isNotEmpty() && packet.url != videoUrl) loadVideo(packet.url, packet.lang)

        when (state) {
            WatchPartySessionState.PLAYING, WatchPartySessionState.PAUSED ->
                timelineFollower.apply(packet.positionMs, packet.serverTimeMs, packet.paused, loop = false)

            else -> if (!isPaused && videoStarted) applyServerPaused(true)
        }
    }

    /** Forces the pause state and starts playback; used by the sync controller before the video has started. */
    internal fun beginPlaybackPaused(desiredPaused: Boolean) {
        paused = desiredPaused
        startVideo()
    }

    /** True if the media player's playback clock is currently advancing. */
    internal fun isClockRunning(): Boolean = mediaPlayer?.isClockRunning() == true

    /** The current media/player generation, used by the sync controller to detect stale video swaps. */
    internal val mediaGeneration: Long get() = media.generationNow

    /** Recreates the GPU texture (e.g. after a resolution change). */
    fun reloadTexture() = createTexture()

    /** Pushes the current quality setting to the media player (clamped to [qualityCap]). */
    fun reloadQuality() {
        mediaPlayer?.setQuality(effectiveQuality())
    }

    /** Returns true if [pos] falls within the screen's block bounding box. */
    fun isInScreen(pos: BlockPos): Boolean =
        DisplayGeometry.isInBounds(pos, x, y, z, width, height, facing)

    /** Returns the shortest Euclidean distance from [pos] to any block in the screen's bounding box. */
    fun getDistanceToScreen(pos: BlockPos): Double =
        DisplayGeometry.distanceTo(pos, x, y, z, width, height, facing)

    /** Builds the world-space planar sound source fed to the acoustics engine (see [tick]). */
    private fun toSourcePlane(): SourcePlane {
        val pose = DisplayGeometry.worldPose(x, y, z, width, height, facing)
        return SourcePlane(
            pose.centerX, pose.centerY, pose.centerZ,
            pose.normalX, pose.normalY, pose.normalZ,
            pose.uAxisX, pose.uAxisY, pose.uAxisZ,
            pose.vAxisX, pose.vAxisY, pose.vAxisZ,
            width.toDouble(), height.toDouble(),
        )
    }

    /** Uploads the latest decoded frame to the GPU texture(s). Called on the render thread once per frame. */
    fun fitTexture() {
        val mp = mediaPlayer ?: return
        frameUploader.upload(mp, textureResource, ::markRendered)
    }

    /**
     * Renders the current frame to the popout window.
     * Must be called after all Minecraft / mod rendering for the frame is complete so that any
     * GL-context switch (`GLFW` backend on macOS) does not corrupt in-flight command buffers.
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
        if (!canPopoutHere) return
        val mp = mediaPlayer ?: return
        popoutManager.activateWindowMode(mp, textureWidth, textureHeight) { videoContentAspect }
    }

    /** Shows the in-game PiP overlay at [corner]. Closes window mode if active. */
    fun activatePipMode(corner: PipCorner = PipCorner.BOTTOM_RIGHT) {
        if (!canPopoutHere) return
        val mp = mediaPlayer ?: return
        popoutManager.activatePipMode(mp, corner) { videoContentAspect }
    }

    /** Closes whichever popout mode is active. */
    fun deactivatePopout() {
        popoutManager.deactivate(mediaPlayer)
    }

    /** Whether the active fullscreen overlay should stay open (re-showing) past the video's end instead of auto-closing. */
    private var fullscreenLoop = false

    /** Shows this display's video as a fullscreen overlay in [mode]. Closes PiP if active. */
    fun activateFullscreenMode(
        mode: FullscreenMode = FullscreenMode.STANDARD,
        forced: Boolean = false,
        sessionId: String? = null,
        loop: Boolean = false,
    ) {
        if (!canPopoutHere && !forced) return
        val mp = mediaPlayer ?: return
        fullscreenLoop = loop
        popoutManager.activateFullscreenMode(mp, mode, forced, sessionId) { videoContentAspect }
    }

    /** Closes the fullscreen overlay, keeping other popout surfaces alive. */
    fun deactivateFullscreen() {
        popoutManager.deactivateFullscreen(mediaPlayer)
    }

    /**
     * Swaps the fullscreen overlay for a PiP corner overlay (forced-broadcast Esc behavior); the resulting PiP can't be
     * clicked open to reconfigure, since a forced broadcast's settings are the caller's, not the viewer's.
     */
    fun minimizeFullscreenToPip() {
        popoutManager.minimizeFullscreenToPip(mediaPlayer, interactive = false)
    }

    /** True while this display is shown in the fullscreen overlay. */
    val isFullscreenActive: Boolean get() = popoutManager.isFullscreenActive

    /** Applies volume, brightness, and paused state to the media player, then seeks to the saved position. */
    fun startVideo() = media.start()

    /** Whether playback is currently paused. */
    val isPaused: Boolean get() = paused

    /** User intent to pause / resume: applied locally for instant feedback and emitted upstream per mode. */
    fun setPaused(paused: Boolean) {
        if (!canControlPlayback) return
        applyPausedLocal(paused)
        emitPlaybackIntent(if (paused) PlaybackAction.PAUSE else PlaybackAction.PLAY)
    }

    /** Applies the server's pause state without emitting any upstream intent (timeline-follower path). */
    internal fun applyServerPaused(paused: Boolean) = applyPausedLocal(paused)

    /** Toggles the local player's pause state and persists it; never touches the network. */
    private fun applyPausedLocal(paused: Boolean) {
        if (!videoStarted) {
            this.paused = paused
            DisplayRegistry.recordScreen(this)
            waitForMFInit { startVideo() }
            return
        }
        if (this.paused == paused) return
        this.paused = paused
        if (paused) mediaPlayer?.pause() else mediaPlayer?.play()
        ClientSettingsStore.updateSettings(uuid, volume, quality, brightness, muted, paused)
        DisplayRegistry.recordScreen(this)
    }

    /** Marks a local VOD as finished without emitting playback commands upstream; also auto-closes a non-looping fullscreen overlay. */
    internal fun onPlaybackEnded(positionNanos: Long) {
        if (isFullscreenActive && !fullscreenLoop) deactivateFullscreen()

        if (effectiveMode != PlaybackMode.LOCAL) return
        savedTimeNanos = positionNanos.coerceAtLeast(0L)
        if (paused) return
        paused = true
        ClientSettingsStore.updateSettings(uuid, volume, quality, brightness, muted, paused)
        DisplayRegistry.recordScreen(this)
    }

    /** Emits the upstream intent for the current mode (no-op for Local / Broadcast / non-host). */
    private fun emitPlaybackIntent(action: PlaybackAction, positionMs: Long = currentTimeNanos / 1_000_000L) {
        when (effectiveMode) {
            PlaybackMode.SYNCED -> Initializer.sendPacket(PlaybackCommand(uuid, action.wire, positionMs))
            PlaybackMode.WATCH_PARTY -> if (watchParty?.isHost == true)
                Initializer.sendPacket(WatchPartyControl(uuid, action.toWatchPartyAction().wire, positionMs))

            else -> {}
        }
    }

    /** Maps a generic playback action onto its watch-party equivalent. */
    private fun PlaybackAction.toWatchPartyAction(): WatchPartyAction = when (this) {
        PlaybackAction.PLAY -> WatchPartyAction.RESUME
        PlaybackAction.PAUSE -> WatchPartyAction.PAUSE
        PlaybackAction.SEEK -> WatchPartyAction.SEEK
        PlaybackAction.RESTART -> WatchPartyAction.RESTART
    }

    /** Seeks [seconds] seconds relative to the current playback position (negative = backward). */
    fun seekVideoRelative(seconds: Double) {
        if (!canSeekHere) return
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekRelative(seconds)
    }

    /** Seeks to an absolute position [nanos] without firing the sync event (used for incoming sync packets). */
    internal fun seekVideoTo(nanos: Long) {
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekTo(nanos, false)
    }

    /** Seeks to [ms] and fires the seek event so [afterSeek] emits the intent upstream (Synced / WP host). */
    fun seekToMillis(ms: Long) {
        if (!canSeekHere) return
        val mp = mediaPlayer ?: return
        if (mp.canSeek()) mp.seekTo(ms * 1_000_000L, true)
    }

    /** Stops the media player, releases GPU texture, closes any popout, and closes the display menu if open. */
    fun unregister() {
        captureReplayCache()
        val currentPlayer = media.shutdown()
        popoutManager.unregister(currentPlayer)
        currentPlayer?.stop()

        textureResource.releaseAsync()
        previewFrameCache?.closeAsync()
        previewFrameCache = null

        val mc = Minecraft.getInstance()
        val screen = MinecraftScreenUtil.currentScreen(mc)
        if (screen is DisplayMenu && screen.displayScreen === this) screen.onClose()
    }

    /** Captures a native replay snapshot before a local display is softly unloaded. */
    private fun captureReplayCache() {
        if (mode != PlaybackMode.LOCAL || watchParty != null || isLive) return
        val url = videoUrl ?: return
        val mp = mediaPlayer ?: return
        val position = currentTimeNanos
        val started = System.nanoTime()
        val snapshot = mp.captureReplaySnapshot() ?: return
        val audioPcm = mp.captureReplayAudio()
        val prepared = mp.capturePreparedMedia()
        DisplayReplayCache.put(uuid, url, position, snapshot, audioPcm, prepared)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        logger.debug(
            "$uuid captured replay snapshot bytes=${snapshot.size} audioPcm=${audioPcm?.size ?: 0}B at " +
                    "${"%.1f".format(position / 1_000_000.0)} ms in ${"%.1f".format(elapsedMs)} ms.",
        )
    }

    /** Mutes or unmutes the screen; no-op if already in the requested state. */
    fun mute(status: Boolean) {
        if (muted == status) return
        muted = status
        applyEffectiveVolume()
        ClientSettingsStore.updateSettings(uuid, volume, quality, brightness, muted, paused)
        DisplayRegistry.recordScreen(this)
    }

    /** Enables or disables the 3D acoustics engine for this display; no-op if already in that state. */
    fun setAcoustics(enabled: Boolean) {
        if (acousticsEnabled == enabled) return
        acousticsEnabled = enabled
        ClientSettingsStore.setAcousticsEnabled(uuid, enabled)
    }

    /** Applies temporary focus mute without changing the user's persisted mute setting. */
    fun setFocusMuted(status: Boolean) {
        if (focusMuted == status) return
        focusMuted = status
        applyEffectiveVolume()
    }

    /** Allocates (or reallocates) the GPU texture and render type for the current quality setting. */
    fun createTexture() {
        hasEverRendered = false
        firstFrameNanos = 0L
        textureResource.allocate(width, height, parseQualityOrDefault())
    }

    /**
     * Stages a new-resolution texture for a quality switch without dropping the current one, so the
     * live frame keeps rendering until the first new frame arrives (see [fitTexture]). Render thread only.
     */
    fun beginQualityHandoff() {
        textureResource.allocatePending(width, height, parseQualityOrDefault())
    }

    /** Drops any staged quality-handoff texture (e.g. when a full session restart supersedes it). */
    fun cancelQualityHandoff() {
        textureResource.discardPendingAsync()
    }

    /** Switches the persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`); the server validates and echoes. */
    fun requestMode(newMode: PlaybackMode) {
        if (!canSetModeHere) return
        if (PlaybackMode.isBaseMode(newMode)) {
            Initializer.sendPacket(SetMode(uuid, newMode.wire, currentTimeNanos / 1_000_000L))
        }
    }

    /** Starts a watch party here with the current (or given) video; the local player becomes host. */
    fun startWatchParty(url: String = videoUrl ?: "", lang: String = this.lang ?: "") =
        watchPartyController.start(url, lang)

    /** Marks the local player ready / not-ready in the active watch party. */
    fun setWatchPartyReady(ready: Boolean) = watchPartyController.setReady(ready)

    /** Host action: starts the countdown for the active watch party. */
    fun beginWatchParty() = watchPartyController.begin()

    /** Host action: ends the active watch party (freezes on the final frame). */
    fun endWatchParty() = watchPartyController.end()

    /** Host action: restarts an ended watch party from preparation. */
    fun restartWatchParty() = watchPartyController.restart()

    /** Closes the watch party, handing the display back to its base mode (host / owner / admin). */
    fun closeWatchParty() = watchPartyController.close()

    /** Seeks to the saved playback position after reconnection; only meaningful for Local displays. */
    fun restoreSavedTime() {
        if (mode != PlaybackMode.LOCAL) return
        val mp = mediaPlayer ?: return
        if (abs(mp.getCurrentTime() - savedTimeNanos) <= RESTORE_SEEK_TOLERANCE_NS) return
        if (savedTimeNanos > 0) mp.seekTo(savedTimeNanos, false)
    }

    /**
     * Applies the current effective volume (mute + distance attenuation) to [mp] up-front, before its
     * reappearance-bridge prelude audio can be heard, so a returning display never blasts a moment of
     * full-volume cached sound. Mirrors the per-tick logic in [tick] / [applyEffectiveVolume].
     */
    internal fun primeNewPlayerVolume(mp: MediaPlayer) {
        val player = Minecraft.getInstance().player ?: return
        val maxRadius = if (isPopoutActive) Double.MAX_VALUE else ClientStateManager.config.defaultDistance.toDouble()
        mp.primeVolume(if (muted || focusMuted) 0f else volume, getDistanceToScreen(player.blockPosition()), maxRadius)
    }

    /** Whether this display can be parked warm when it leaves render distance (Local VOD on the
     *  in-process-libav decoder), rather than torn down and rebuilt from a snapshot on return. */
    fun canWarmPark(): Boolean =
        mode == PlaybackMode.LOCAL && watchParty == null && !isLive && mediaPlayer?.canPark() == true

    /** Parks the display warm: stops rendering/advancing but keeps the decoder + audio open and frozen. */
    fun goDormant() {
        if (isDormant) return
        mediaPlayer?.park()
        dormantSinceNanos = System.nanoTime()
        isDormant = true
    }

    /** Wakes a [goDormant] display: resumes its warm session from the frozen position and renders again. */
    fun wake() {
        if (!isDormant) return
        mediaPlayer?.unpark()
        isDormant = false
    }

    /** True once a dormant display has sat parked longer than [ttlNanos] (caller then tears it down). */
    fun dormantExpired(ttlNanos: Long): Boolean = isDormant && System.nanoTime() - dormantSinceNanos > ttlNanos

    /** Monotonic timestamp when this display entered full warm park, or [Long.MAX_VALUE] when active. */
    fun dormantSinceNanos(): Long = if (isDormant) dormantSinceNanos else Long.MAX_VALUE

    /** Approximate GPU texture bytes held by this display, including staged quality-handoff textures. */
    fun estimatedTextureBytes(): Long = textureResource.estimatedBytes()

    /** Takes a one-shot replay bootstrap matching [url] and this screen's saved restore position. */
    internal fun takeReplayBootstrap(url: String): MediaPlayer.ReplayBootstrap? {
        if (mode != PlaybackMode.LOCAL || watchParty != null || savedTimeNanos <= 0L) return null
        return DisplayReplayCache.take(uuid, url, savedTimeNanos)
    }

    /** Returns true if the media player is ready and the stream supports seeking. */
    fun canSeek(): Boolean = mediaPlayer?.canSeek() == true

    /** Runs [action] once the current media player is initialized; guards against stale generations. */
    fun waitForMFInit(action: () -> Unit) = media.whenInitialized(action)

    /**
     * Resolves the current quality to a target pixel height, clamped to [qualityCap] when set
     * ([VideoQuality.Auto] falls back to [DEFAULT_QUALITY]). Broadcast caps every client at 360p.
     */
    private fun parseQualityOrDefault(): Int {
        if (qualityCap > 0) return qualityCap
        return quality.targetHeight ?: DEFAULT_QUALITY
    }

    /** Last raytraced acoustic environment, refreshed on the [ENV_PROBE_INTERVAL_TICKS] cadence. */
    private var cachedEnvironment: AcousticEnvironment = AcousticEnvironment.OPEN_AIR

    /** Ticks remaining before the next voxel-acoustics re-probe; jittered per-display to avoid synchronized spikes. */
    private var envProbeCountdown: Int = uuid.hashCode().mod(ENV_PROBE_INTERVAL_TICKS)

    /** Called every game tick to update distance-based volume attenuation from [pos]. */
    fun tick(pos: BlockPos) {
        val maxRadius = if (isPopoutActive) Double.MAX_VALUE else ClientStateManager.config.defaultDistance.toDouble()
        mediaPlayer?.tick(getDistanceToScreen(pos), maxRadius)
        if (ClientStateManager.config.audioAcoustics == AcousticQuality.OFF) return
        val plane = toSourcePlane()
        DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.updateSource(
            uuid,
            SourceAcousticState(
                plane = plane,
                userVolume = volume,
                muted = muted || focusMuted,
                bypassSpatial = isPopoutActive,
                acousticsEnabled = acousticsEnabled,
                environment = probeEnvironment(plane),
            ),
        )
    }

    /**
     * Returns the display's current acoustic environment, re-running the (relatively costly) voxel
     * raytrace only every [ENV_PROBE_INTERVAL_TICKS] ticks and reusing the cached result between probes;
     * the DSP chain smooths across the gap so the cadence is inaudible. Skips the trace entirely when
     * spatial acoustics can't use it (popout, per-display opt-out, or a tier below ADVANCED).
     */
    private fun probeEnvironment(plane: SourcePlane): AcousticEnvironment {
        val tier = ClientStateManager.config.audioAcoustics
        if (isPopoutActive || !acousticsEnabled || (tier != AcousticQuality.ADVANCED && tier != AcousticQuality.ULTRA)) {
            cachedEnvironment = AcousticEnvironment.OPEN_AIR
            envProbeCountdown = 0
            return cachedEnvironment
        }
        if (envProbeCountdown <= 0) {
            cachedEnvironment =
                VoxelAcousticsProbe.probe(plane, ListenerPoseTracker.currentPose(Minecraft.getInstance()))
            envProbeCountdown = ENV_PROBE_INTERVAL_TICKS
        } else {
            envProbeCountdown--
        }
        return cachedEnvironment
    }

    /** Called after a user-initiated seek completes; emits the seek intent upstream per mode. */
    fun afterSeek() {
        if (!canSeekHere) return
        emitPlaybackIntent(PlaybackAction.SEEK)
    }

    companion object {
        /** Logger for replay-capture and diagnostic messages. */
        private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayScreen")

        /** Ticks between voxel-acoustics re-probes; the DSP chain smooths across this gap. */
        private const val ENV_PROBE_INTERVAL_TICKS = 2

        /** Initial per-display volume for newly seen displays. */
        internal fun defaultVolume(): Float {
            val serverDefault = ClientPacketManager.serverSnapshot.defaultVolume
            if (serverDefault >= 0f) return serverDefault.coerceIn(0f, MAX_SERVER_DEFAULT_VOLUME) // No to bad servers
            return ClientDisplaySettings.DEFAULT_VOLUME
        }

        /** Fallback target quality (pixel height) when none is resolvable. */
        private const val DEFAULT_QUALITY = 720

        /** Skip the restore seek when already within this tolerance of the saved position. */
        private const val RESTORE_SEEK_TOLERANCE_NS = 250_000_000L

        /** Duration of the first-frame fade-in (see [appearProgress]). */
        private const val APPEAR_FADE_NANOS = 260_000_000L

        /**
         * Self-heal timeout for [waitingForInitialTimeline] (see [stillWaitingForInitialTimeline]):
         * well over the server's periodic timeline-broadcast interval, so it never fires in the
         * healthy case, but guarantees the picture isn't stranded forever behind "Waiting for video..."
         * if that first packet is ever dropped, superseded, or simply never arrives.
         */
        private const val WAITING_FOR_TIMELINE_TIMEOUT_NANOS = 5_000_000_000L

        /** Maximum server-prescribed default volume accepted by the client (200% in the UI). */
        private const val MAX_SERVER_DEFAULT_VOLUME = 1.0f
    }
}
