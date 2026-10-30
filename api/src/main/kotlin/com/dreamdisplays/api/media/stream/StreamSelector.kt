package com.dreamdisplays.api.media.stream

interface StreamSelector {
    fun select(streams: List<MediaStream>, preferences: StreamPreferences): StreamSet
}
