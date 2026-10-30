package com.dreamdisplays.api

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.event.Events
import com.dreamdisplays.api.display.data.DisplayCustomData
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.playback.model.PlaybackMode
import java.util.function.Consumer

/**
 * A live display.
 *
 * It holds no video or memory, and it stays safe to use after the display is removed
 * (commands then return [CommandResult.STALE] and setters do nothing).
 *
 * Example:
 * ```
 * val display = DreamDisplays.display(id) ?: return
 * display.url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
 * display.play()
 * display.seek(30_000)
 * display.volume = 0.5f
 * ```
 *
 * @since 1.0.x
 */
@Unstable
interface Display {
    /** Persistent ID of this display. */
    val id: DisplayId

    /** Current snapshot, or `null` when the display no longer exists. */
    val info: DisplayInfo?

    /** `false` once the display has been removed. */
    val isAlive: Boolean

    /**
     * Runtime state.
     *
     * [DisplayState.Idle] when the display no longer exists.
     */
    val state: DisplayState

    /** `true` while the video is playing. */
    val isPlaying: Boolean

    /** Data addons keep on this display. */
    val customData: DisplayCustomData

    /**
     * The video link, or `null` when none is set.
     *
     * Setting it asks for a video change.
     */
    var url: String?

    /**
     * Volume multiplier
     *
     * Setting it applies the change.
     */
    var volume: Float

    /** Brightness multiplier (0.0–2.0). */
    var brightness: Float

    /** Whether audio is muted. */
    var muted: Boolean

    /** Requested video quality. */
    var quality: VideoQuality

    /** Playback mode: `Local`, `Synced`, `Watch party` or `Broadcast`. */
    var mode: PlaybackMode

    /** Events of this display only. */
    val events: Events

    /** Starts or resumes playback. */
    fun play(): CommandResult

    /** Pauses playback. */
    fun pause(): CommandResult

    /** Stops playback and clears the video. */
    fun stop(): CommandResult

    /** Restarts the video from the beginning. */
    fun restart(): CommandResult

    /** Reloads the video after a failure. */
    fun retry(): CommandResult

    /** Jumps to [positionMs] milliseconds from the start. */
    fun seek(positionMs: Long): CommandResult

    /** Jumps [deltaMs] milliseconds from the current position (negative = backwards). */
    fun seekBy(deltaMs: Long): CommandResult

    /** Sets the video link together with the preferred audio language [lang]. */
    fun setUrl(url: String?, lang: String?): CommandResult

    /** Picks the audio track by its stream URL. */
    fun setAudioTrack(trackUrl: String): CommandResult

    /**
     * Turns subtitles on for language [lang], or off when `null`.
     *
     * Only for this viewer.
     */
    fun setSubtitles(lang: String?): CommandResult

    /** Removes this display (owner or admin only). */
    fun delete(): CommandResult

    /** Reports this display for moderation. */
    fun report(): CommandResult

    /**
     * Subscribes to events of this display only. The subscription ends by itself when the display is removed;
     * close the returned handle to end it earlier.
     *
     * *Deprecated. Use [events].*
     */
    @Deprecated(
        "Use events.on(type, listener).",
        ReplaceWith("events.on(DisplayEvent::class.java, listener)"),
    )
    fun on(listener: Consumer<DisplayEvent>): AutoCloseable

    /**
     * Describes how to react to the display's life in one block; ends by itself when the display is removed.
     *
     * `Kotlin` code can use the nicer `display.lifecycle { onPlaying { ... } }` extension.
     *
     * *Deprecated. Use [events].*
     */
    @Deprecated(
        "Use events.lifecycle(configure).",
        ReplaceWith("events.lifecycle(configure)"),
    )
    fun watch(configure: Consumer<DisplayLifecycle>): AutoCloseable
}
