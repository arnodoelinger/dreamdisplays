package com.dreamdisplays.platform.client.displays

//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.api.display.model.ContentRotation
import com.dreamdisplays.api.display.model.DisplayFacing
import com.dreamdisplays.api.media.common.DreamMediaException
import com.dreamdisplays.api.media.common.VideoQuality
import com.dreamdisplays.api.media.audio.*
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.playback.*
import com.dreamdisplays.api.display.model.ClientDisplaySettings
import com.dreamdisplays.api.watchparty.WatchPartySession
import com.dreamdisplays.core.protocol.*
import com.dreamdisplays.core.protocol.packets.*
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
import com.dreamdisplays.platform.client.ui.widgets.SuggestionsController
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import com.dreamdisplays.util.FacingUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.math.abs

/** Represents a video display screen in the game world. */
class DisplayScreen(
    /** Stable unique id of this display, shared with the server. */
    val uuid: UUID,

    /** ID of the player who created and owns this display. */
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

    /** Epoch millis of a pending scheduled play / pause, or `0` when none is set (see [com.dreamdisplays.core.protocol.packets.DisplayInfo]). */
    var scheduledStartEpochMillis: Long = 0

    /** Wire ordinal of the scheduled [com.dreamdisplays.api.playback.PlaybackAction] (`PLAY`/`PAUSE`), or `-1` when none is set. */
    var scheduledAction: Int = -1

    /** Monotonic mark of the last [retryVideo], or `null` if never retried this session. */
    @Volatile
    private var lastRetryMark: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * The last media failure on this display, or `null` when healthy. New failures within
     * [ERROR_RETRY_COOLDOWN] of a retry are swallowed.
     */
    @Volatile
    var mediaError: DreamMediaException? = null
        set(value) {
            if (value != null && lastRetryMark?.elapsedNow()?.let { it < ERROR_RETRY_COOLDOWN } == true) return
            field = value
        }

    /** True while a media error is active. */
    val errored: Boolean get() = mediaError != null

    /** True if the local player may edit this display (owner, admin, or not effectively locked). */
    val canEdit: Boolean get() = owner || isAdmin || !effectiveLocked

    /** Whether the user has muted this display. */
    var muted: Boolean = savedSettings.muted

    /** 3D acoustics engine (directivity, occlusion, reverb); false = legacy distance-gain only. */
    var acousticsEnabled: Boolean = savedSettings.acousticsEnabled

    /** Legacy mirror of [mode]; true only for [PlaybackMode.SYNCED]. */
    val isSync: Boolean get() = mode == PlaybackMode.SYNCED

    /** Live watch-party session over this display, or null when none is running. */
    @Volatile
    var watchParty: WatchPartySession? = null; internal set

    /** Whether the local player has marked themselves ready in the current session (UI toggle state). */
    @Volatile
    var localWatchPartyReady: Boolean = false; internal set

    /** Suggestions-panel state; survives menu close/reopen. */
    val suggestionsController = SuggestionsController()

    /** The effective mode the player experiences — `WATCH_PARTY` while a session is live. */
    val effectiveMode: PlaybackMode get() = if (watchParty != null) PlaybackMode.WATCH_PARTY else mode

    /** Local player permission context. */
    private fun ctx(): PlaybackContext = PlaybackContext(
        mode = effectiveMode,
        isOwner = owner,
        isAdmin = isAdmin,
        isLocked = PlaybackPermissions.isEffectivelyLocked(effectiveMode, isLocked == true),
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
            mediaPlayer?.setQuality(effectiveQuality(value), userInitiated = qualityWriteIsViewerAction)
            ClientSettingsStore.updateSettings(uuid, volume, value, brightness, muted, paused)
            DisplayRegistry.recordScreen(this)
        }

    /** Set only for the duration of a [setQualityByViewer] write; see that method. Main thread only. */
    private var qualityWriteIsViewerAction = false

    /**
     * Applies a viewer-chosen [value]. Identical to assigning [quality], except the player is told the
     * change was asked for, so the UI may report it as being applied. Settings restores, server echoes
     * and the automatic distance ladder all assign [quality] directly and stay silent — otherwise every
     * video swap would flash an "applying quality" hint the viewer never triggered.
     */
    fun setQualityByViewer(value: VideoQuality) {
        qualityWriteIsViewerAction = true
        try {
            quality = value
        } finally {
            qualityWriteIsViewerAction = false
        }
    }

    /** Requested audio track (stream URL); respawns audio only, not persisted. */
    var audioTrack: String = ""
        set(value) {
            field = value
            mediaPlayer?.setAudioTrack(value)
        }

    /** Broadcast pins to cap; otherwise applies distance steps. */
    private fun effectiveQuality(requested: VideoQuality = quality): VideoQuality {
        val base = if (qualityCap > 0) VideoQuality.Fixed(qualityCap) else requested
        return applyDistanceSteps(base)
    }

    /** How many rungs down [QUALITY_LADDER] the current distance has pushed the effective quality; 0 when close. */
    private var distanceQualitySteps = 0

    /** Re-derives distance quality steps and re-pushes if changed. */
    private fun updateDistanceQuality(fraction: Float) {
        var steps = distanceQualitySteps
        while (steps < DISTANCE_STEP_THRESHOLDS.size && fraction >= DISTANCE_STEP_THRESHOLDS[steps]) steps++
        while (steps > 0 && fraction < DISTANCE_STEP_THRESHOLDS[steps - 1] - DISTANCE_STEP_HYSTERESIS) steps--
        if (steps == distanceQualitySteps) return
        distanceQualitySteps = steps
        reloadQuality()
    }

    /** Moves [base] down [distanceQualitySteps] rungs in [QUALITY_LADDER]; a no-op at 0 steps. */
    private fun applyDistanceSteps(base: VideoQuality): VideoQuality {
        if (distanceQualitySteps <= 0) return base
        val baseHeight = base.targetHeight ?: DEFAULT_QUALITY
        val idx = QUALITY_LADDER.indexOfFirst { it <= baseHeight }.let { if (it < 0) QUALITY_LADDER.lastIndex else it }
        val target = QUALITY_LADDER[(idx + distanceQualitySteps).coerceAtMost(QUALITY_LADDER.lastIndex)]
        return VideoQuality.Fixed(target)
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

    /** Warm-parked out of render distance: decoder + audio open, instant resume. */
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

    /** True once a frame uploaded; holds last frame across quality handoff. Reset by [createTexture]. */
    @Transient
    @Volatile
    private var hasEverRendered = false

    /** [System.nanoTime] of the first uploaded frame, driving to appear fade-in. `0` = none yet. */
    @Transient
    @Volatile
    private var firstFrameNanos = 0L

    /** True while waiting for the server's first timeline before showing the video (Synced / Broadcast / WP). */
    @Transient
    @Volatile
    private var waitingForInitialTimeline = false

    /** Timeline wait timeout tracker (self-heals stranded "Waiting..." on stale packet). */
    @Transient
    @Volatile
    private var waitingSinceNanos = 0L

    /** Re-checks initial timeline wait; self-clears on timeout. */
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

    /** Marks that a frame has rendered, stamping the first-frame time so to appear fade-in can run. */
    private fun markRendered() {
        if (!hasEverRendered) firstFrameNanos = System.nanoTime()
        hasEverRendered = true
    }

    /** First-frame fade-in progress (0..1 eased); 1 = no fade or replay reappear. */
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

    /** True for a fullscreen broadcast's synthetic display. */
    var virtual: Boolean = false

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

    /** Raw stream URL for seek-bar scrub-preview frame extraction; null for live/unresolved. */
    val scrubPreviewRawUrl: String? get() = mediaPlayer?.capturedStreamRawUrl()

    /** Whether scrub previews for this display must seek by decoding forward; see `MediaStream.seekByDecoding`. */
    val scrubPreviewSeeksByDecoding: Boolean get() = mediaPlayer?.capturedStreamSeeksByDecoding() == true

    /** Pixel heights of the qualities available for the current video. */
    val qualityList: List<Int>
        get() = mediaPlayer?.getAvailableQualities() ?: emptyList()

    /** Audio tracks available for the current video (more than one only when the provider exposes dubs). */
    val audioTrackList: List<MediaStream>
        get() = mediaPlayer?.getAvailableAudioTracks() ?: emptyList()

    /** Resolved URL of the audio track currently playing, or empty before a stream has resolved. */
    val currentAudioTrackUrl: String
        get() = mediaPlayer?.getCurrentAudioTrack() ?: ""

    /** True while an audio-track switch is in flight; the audio can lag the picked track by a few seconds. */
    val isSwitchingAudioTrack: Boolean
        get() = mediaPlayer?.isSwitchingAudioTrack() == true

    /** True while a quality change is still being applied; the new resolution lands a few seconds later. */
    val isApplyingQuality: Boolean
        get() = mediaPlayer?.isApplyingQuality() == true

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

    /** Re-attempts current video after failure; purely local, no server packet. */
    fun retryVideo() {
        val url = videoUrl ?: return
        lastRetryMark = TimeSource.Monotonic.markNow()
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
        virtual = packet.virtual
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
        scheduledStartEpochMillis = packet.scheduledStartEpochMillis
        scheduledAction = packet.scheduledAction
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

    /** Applies watch-party snapshot: tracks session, loads host video, follows timeline. */
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

    /** Renders frame to popout; call after all Minecraft/mod rendering to avoid GL-context corruption. */
    fun renderPopout() {
        popoutManager.renderFrame()
    }

    /** Passes [volume] directly to the media player without persisting to settings. */
    fun setVideoVolume(volume: Float) {
        mediaPlayer?.setVolume(volume)
    }

    /** Applies effective volume (0 if muted/unfocused, else user volume). */
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

    /** Last applied fullscreen state; survives server switch but not display re-creation. */
    @Volatile
    internal var lastFullscreenState: FullscreenState? = null

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

    /** Swaps fullscreen to non-interactive PiP (forced-broadcast Esc behavior). */
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
        val audioSnapshot = mp.captureReplayAudio()
        val prepared = mp.capturePreparedMedia()
        DisplayReplayCache.put(
            uuid, url, position, snapshot,
            audioSnapshot?.pcm, audioSnapshot?.sampleRate ?: 0, audioSnapshot?.channels ?: 0,
            prepared,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        logger.debug(
            "{} captured replay snapshot bytes={} audioPcm={}B at {} ms in {} ms.",
            uuid,
            snapshot.size,
            audioSnapshot?.pcm?.size ?: 0,
            "%.1f".format(position / 1_000_000.0),
            "%.1f".format(elapsedMs)
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

    /** Stages new-resolution texture; live frame renders until first new frame (render thread only). */
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

    /** Primes player volume before prelude audio to avoid blast on return. */
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
     * ([VideoQuality.Auto] falls back to [DEFAULT_QUALITY]). Broadcast caps every client at 720p.
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
        val distance = getDistanceToScreen(pos)
        mediaPlayer?.tick(distance, maxRadius)
        if (isPopoutActive) {
            if (distanceQualitySteps != 0) {
                distanceQualitySteps = 0
                reloadQuality()
            }
        } else {
            updateDistanceQuality((distance / renderDistance.coerceAtLeast(1)).toFloat())
        }
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

    /** Acoustic environment (voxel raytrace cached every [ENV_PROBE_INTERVAL_TICKS] ticks). */
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

        /** Grace window after a manual retry during which new media errors are swallowed rather than shown. */
        private val ERROR_RETRY_COOLDOWN = 15.seconds

        /** Initial per-display volume for newly seen displays. */
        internal fun defaultVolume(): Float {
            val serverDefault = ClientPacketManager.serverSnapshot.defaultVolume
            if (serverDefault >= 0f) return serverDefault.coerceIn(0f, MAX_SERVER_DEFAULT_VOLUME) // No to bad servers
            return ClientDisplaySettings.DEFAULT_VOLUME
        }

        /** Fallback target quality (pixel height) when none is resolvable. */
        private const val DEFAULT_QUALITY = 1080

        /** Quality rungs [applyDistanceSteps] moves down through, highest to lowest. */
        private val QUALITY_LADDER = intArrayOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

        /** Distance quality step down-thresholds as render-distance fraction. */
        private val DISTANCE_STEP_THRESHOLDS = floatArrayOf(0.66f, 0.75f)

        /** Hysteresis to prevent quality flip-flop at step boundaries. */
        private const val DISTANCE_STEP_HYSTERESIS = 0.05f

        /** Skip the restore seek when already within this tolerance of the saved position. */
        private const val RESTORE_SEEK_TOLERANCE_NS = 250_000_000L

        /** Duration of the first-frame fade-in (see [appearProgress]). */
        private const val APPEAR_FADE_NANOS = 260_000_000L

        /** Self-heal timeout for [waitingForInitialTimeline] (prevents stranded "Waiting for video..."). */
        private const val WAITING_FOR_TIMELINE_TIMEOUT_NANOS = 5_000_000_000L

        /** Maximum server-prescribed default volume accepted by the client (200% in the UI). */
        private const val MAX_SERVER_DEFAULT_VOLUME = 1.0f
    }
}
