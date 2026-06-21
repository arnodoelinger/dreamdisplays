package com.dreamdisplays.api.media


interface StreamSelector {
    fun select(streams: List<MediaStream>, preferences: StreamPreferences): StreamSet
}
