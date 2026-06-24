package com.dreamdisplays.media.player.stream

import com.dreamdisplays.api.media.stream.MediaStream

interface StreamSelector {
    fun select(streams: List<MediaStream>, preferences: StreamPreferences): StreamSet
}
