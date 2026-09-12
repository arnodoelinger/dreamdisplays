package com.dreamdisplays.api.display.service

import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.settings.DisplaySettings

/**
 * Display mutation port.
 *
 * @since 1.8.x
 */
@Unstable
interface DisplayMutationPort {
    /** Updates the display settings for [id]. */
    fun updateSettings(id: DisplayId, settings: DisplaySettings)

    /** Sets the URL for [id]. */
    fun setUrl(id: DisplayId, url: String?, lang: String? = null)

    /** Sets who may use [id]. */
    fun setAccess(id: DisplayId, access: DisplayAccess)

    /** Deletes [id]. */
    fun delete(id: DisplayId)

    /** Reports [id]. */
    fun report(id: DisplayId)
}
