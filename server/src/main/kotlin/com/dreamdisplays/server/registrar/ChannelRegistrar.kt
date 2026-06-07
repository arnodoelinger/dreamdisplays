package com.dreamdisplays.server.registrar

import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.server.Main
import com.dreamdisplays.server.utils.net.PacketReceiver

/**
 * Manages the registration of plugin channels for incoming and outgoing messages.
 */
@PaperOnly object ChannelRegistrar {
    private val incomingChannels = listOf(
        "dreamdisplays:sync",
        "dreamdisplays:req_sync",
        "dreamdisplays:delete",
        "dreamdisplays:report",
        "dreamdisplays:version",
        "dreamdisplays:display_enabled",
        "dreamdisplays:set_video",
        "dreamdisplays:set_locked"
    )

    private val outgoingChannels = listOf(
        "dreamdisplays:premium",
        "dreamdisplays:is_admin",
        "dreamdisplays:display_info",
        "dreamdisplays:sync",
        "dreamdisplays:delete",
        "dreamdisplays:display_enabled",
        "dreamdisplays:report_enabled",
        "dreamdisplays:clear_cache"
    )

    /** Registers all incoming and outgoing plugin messaging channels for this plugin. */
    fun registerChannels(plugin: Main) {
        val messenger = plugin.server.messenger
        val receiver = PacketReceiver(plugin)

        incomingChannels.forEach { messenger.registerIncomingPluginChannel(plugin, it, receiver) }
        outgoingChannels.forEach { messenger.registerOutgoingPluginChannel(plugin, it) }
    }
}
