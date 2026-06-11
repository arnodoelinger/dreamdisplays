package com.dreamdisplays.media.api

/**
 * What a [MediaStream] carries. Drives track selection: a video-only and an audio-only stream are
 * paired for playback, while a muxed [VIDEO_AUDIO] stream stands alone.
 *
 * @since 1.6.0
 */
enum class MediaStreamType {
    /** Video track only (no audio). */
    VIDEO,

    /** Audio track only (no video). */
    AUDIO,

    /** A single muxed stream carrying both video and audio. */
    VIDEO_AUDIO;

    /** True for [VIDEO] or [VIDEO_AUDIO]. */
    val hasVideo: Boolean get() = this == VIDEO || this == VIDEO_AUDIO

    /** True for [AUDIO] or [VIDEO_AUDIO]. */
    val hasAudio: Boolean get() = this == AUDIO || this == VIDEO_AUDIO
}
