@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.media.api

import com.dreamdisplays.api.DreamDisplaysUnstableApi

interface AudioSink : AutoCloseable {
    fun onAudioData(pcmData: ByteArray, timestampUs: Long)
    fun setVolume(volume: Float)
    fun flush()
    val isAvailable: Boolean
}
