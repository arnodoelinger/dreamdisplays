package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.api.media.audio.service.keys.AudioAcousticsServices
import com.dreamdisplays.api.runtime.module.DreamDisplaysModule
import com.dreamdisplays.api.runtime.module.ModuleContext
import com.dreamdisplays.media.audio.engine.AcousticsEngine
import com.dreamdisplays.platform.client.managers.ClientStateManager

/** Installs the 3D acoustics engine and seeds it with the current config (quality tier, output profile). */
object ClientAudioModule : DreamDisplaysModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplays:client_audio"

    /** Creates the [AcousticsEngine] and registers it under [AudioAcousticsServices.ACOUSTICS]. */
    override fun install(context: ModuleContext) {
        val engine = AcousticsEngine()
        engine.setGlobalQuality(ClientStateManager.config.audioAcoustics)
        engine.setBinauralOutput(ClientStateManager.config.audioBinauralOutput)
        context.services.register(AudioAcousticsServices.ACOUSTICS, engine)
    }
}
