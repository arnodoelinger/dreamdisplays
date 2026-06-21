package com.dreamdisplays.application.display

import com.dreamdisplays.core.display.DisplayId
import com.dreamdisplays.core.display.DisplaySettings

interface DisplayMutationPort {
    fun updateSettings(id: DisplayId, settings: DisplaySettings)
    fun setUrl(id: DisplayId, url: String?)
}
