@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.proxy.packets

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Asks network what video the display named by [token] is playing (full id or 8-char abbreviation). */
@Serializable
data class ResolveDisplayToken(
    @ProtoNumber(1) val requestId: String = "",
    @ProtoNumber(2) val token: String = "",
    @ProtoNumber(3) val originServer: String = "",
) : ProxyPacket

/** Answers [ResolveDisplayToken] from the backend hosting the display, with current video URL. */
@Serializable
data class DisplayTokenResolved(
    @ProtoNumber(1) val requestId: String = "",
    @ProtoNumber(2) val originServer: String = "",
    @ProtoNumber(3) val url: String = "",
) : ProxyPacket

/** One display a backend hosts, as advertised in a [BackendDisplayIndex]. */
@Serializable
data class DisplayIndexEntry(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val url: String = "",
)

/** Backend advertises all displays it hosts, enabling proxy to answer [ResolveDisplayToken] queries. */
@Serializable
data class BackendDisplayIndex(
    @ProtoNumber(1) val displays: List<DisplayIndexEntry> = emptyList(),
) : ProxyPacket
