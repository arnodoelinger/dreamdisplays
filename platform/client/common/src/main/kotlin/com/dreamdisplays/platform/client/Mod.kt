package com.dreamdisplays.platform.client

import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Custom packet sender. */
fun interface Mod {
    fun sendPacket(packet: CustomPacketPayload)
}
