package com.dreamdisplays.core.display.service

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplaySettings

interface DisplayMutationPort {
    fun updateSettings(id: DisplayId, settings: DisplaySettings)
    fun setUrl(id: DisplayId, url: String?, lang: String? = null)
    fun setLocked(id: DisplayId, locked: Boolean)
    fun delete(id: DisplayId)
    fun report(id: DisplayId)
}
