package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.utils.net.PacketReceiver
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.V2_CHANNEL
import io.github.arnodoelinger.platformweaver.PaperOnly

/**
 * Manages the registration of plugin channels for incoming and outgoing messages.
 */
@PaperOnly
object ChannelRegistrar {
    /** Incoming plugin channels. */
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

    /** Outgoing plugin channels. */
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
    fun registerChannels(plugin: PaperServer) {
        val messenger = plugin.server.messenger
        val receiver = PacketReceiver(plugin)

        incomingChannels.forEach { messenger.registerIncomingPluginChannel(plugin, it, receiver) }
        outgoingChannels.forEach { messenger.registerOutgoingPluginChannel(plugin, it) }

        messenger.registerIncomingPluginChannel(plugin, V2_CHANNEL, PaperV2Networking)
        messenger.registerOutgoingPluginChannel(plugin, V2_CHANNEL)
    }
}
