package com.dreamdisplays.render.api

/**
 * Strategy for handling decoded frames that arrive faster than they can be uploaded, i.e. how a
 * render surface trades latency against smoothness when its frame queue backs up.
 *
 * @since 1.8.0
 */
enum class FrameDropPolicy {
    /** Never drop; every decoded frame is uploaded even if it adds latency. */
    NEVER,

    /** Drop the oldest queued frames first, keeping playback close to real time. */
    DROP_OLDEST,

    /** Drop frames identical to the one already on screen (e.g. paused or static content). */
    DROP_DUPLICATES,

    /** Switch between policies at runtime based on current queue depth and frame rate. */
    ADAPTIVE,
}
