package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.api.media.audio.AudioAcousticsServices
import com.dreamdisplays.api.runtime.module.DreamDisplaysModule
import com.dreamdisplays.api.runtime.module.ModuleContext
import com.dreamdisplays.media.audio.engine.AcousticsEngine
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.platform.client.managers.ClientStateManager

/**
 * Installs the 3D acoustics engine and seeds it with the current config (quality tier, output profile).
 * The engine's global listener pose / quality tier / binaural toggle are also forwarded to the native
 * audio engine (see [NativeMedia.lavAudioSetListener]), which owns the actual DSP chain for every
 * in-process audio session; the [AcousticsEngine] instance itself stays the source of truth and the
 * per-source registry the geometry probe publishes into.
 */
object ClientAudioModule : DreamDisplaysModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplays:client_audio"

    /** Creates the [AcousticsEngine] and registers it under [AudioAcousticsServices.ACOUSTICS]. */
    override fun install(context: ModuleContext) {
        val engine = AcousticsEngine(
            onListenerChanged = NativeMedia::lavAudioSetListener,
            onQualityChanged = NativeMedia::lavAudioSetQuality,
            onBinauralChanged = NativeMedia::lavAudioSetBinaural,
        )
        engine.setGlobalQuality(ClientStateManager.config.audioAcoustics)
        engine.setBinauralOutput(ClientStateManager.config.audioBinauralOutput)
        context.services.register(AudioAcousticsServices.ACOUSTICS, engine)
    }
}
