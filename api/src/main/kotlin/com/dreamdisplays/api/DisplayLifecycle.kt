package com.dreamdisplays.api

import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.api.media.model.DreamMediaException
import java.util.function.Consumer

/**
 * Handlers for the stages of a display's life
 *
 * @since 1.10.x
 * @see [Display.lifecycle]
 */
interface DisplayLifecycle {
    /** The video started playing (or resumed). */
    fun onPlaying(action: Consumer<DisplayState.Playing>)

    /** The video was paused. */
    fun onPaused(action: Consumer<DisplayState.Paused>)

    /** The video is waiting for data. */
    fun onBuffering(action: Consumer<DisplayState.Buffering>)

    /** Playback was stopped. */
    fun onStopped(action: Runnable)

    /** The video could not be loaded or played. */
    fun onFailed(action: Consumer<DreamMediaException>)

    /** The video link changed. */
    fun onUrlChanged(action: Consumer<String?>)

    /** Addon data on the display changed; the action gets the keys that changed. */
    fun onDataChanged(action: Consumer<Set<String>>)

    /** The display was removed. After this, nothing else is delivered. */
    fun onRemoved(action: Runnable)
}
