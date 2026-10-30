package com.dreamdisplays.api.display.data

/**
 * A read-only [DisplayCustomData] over a map; what the client hands out.
 *
 * @since 1.10.x
 */
class MapCustomData(private val source: () -> Map<String, ByteArray>) : DisplayCustomData {
    override val keys: Set<String> get() = source().keys
    override fun get(key: String): ByteArray? = source()[key]?.copyOf()
    override val isEmpty: Boolean get() = source().isEmpty()
    override fun toString(): String = "DisplayCustomData$keys"
}
