package com.dreamdisplays.core.display

interface DisplayMutationPort {
    fun updateSettings(id: DisplayId, settings: DisplaySettings)
    fun setUrl(id: DisplayId, url: String?)
}
