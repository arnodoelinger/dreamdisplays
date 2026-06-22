package com.dreamdisplays.platform.client

import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Custom packet sender. */
fun interface Mod {
    /** Sends [packet] to the connected server over the platform's networking. */
    fun sendPacket(packet: CustomPacketPayload)
}
