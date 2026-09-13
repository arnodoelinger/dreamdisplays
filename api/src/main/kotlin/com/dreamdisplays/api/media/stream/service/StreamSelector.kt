package com.dreamdisplays.api.media.stream.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.stream.model.MediaStream
import com.dreamdisplays.api.media.stream.model.StreamPreferences
import com.dreamdisplays.api.media.stream.model.StreamSet

@Unstable
interface StreamSelector {
    fun select(streams: List<MediaStream>, preferences: StreamPreferences): StreamSet
}
