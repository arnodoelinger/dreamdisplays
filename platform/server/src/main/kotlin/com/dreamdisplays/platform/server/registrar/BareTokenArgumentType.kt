package com.dreamdisplays.platform.server.registrar

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.commands.synchronization.ArgumentTypeInfos
import net.minecraft.core.registries.BuiltInRegistries
//? if >=1.21.11 {
import net.minecraft.resources.Identifier

//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * Space-delimited unquoted token for bare selectors, URLs in `/display fullscreen` commands.
 */
object BareTokenArgumentType : ArgumentType<String> {
    private val MISSING = SimpleCommandExceptionType(LiteralMessage("Expected a value."))
    val ID: Identifier = Identifier.fromNamespaceAndPath("dreamdisplays", "bare_token")

    @Suppress("UNCHECKED_CAST")
    val NETWORK_FALLBACK: ArgumentType<Any> = StringArgumentType.greedyString() as ArgumentType<Any>

    override fun parse(reader: StringReader): String {
        val start = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        if (reader.cursor == start) throw MISSING.create()
        return reader.string.substring(start, reader.cursor)
    }
}

/**
 * Registers [BareTokenArgumentType]'s sync via reflection to alias it to [StringArgumentType].
 * This avoids registering a custom ID in `COMMAND_ARGUMENT_TYPE`, which would kick vanilla clients.
 */
@FabricOnly
object FabricBareTokenArgumentType {
    private var registered = false

    /** Idempotent; call once, early, from mod init. */
    fun register() {
        if (registered) return
        registered = true
        try {
            val field = ArgumentTypeInfos::class.java.getDeclaredField("BY_CLASS")
            field.isAccessible = true
            val byClass = field.get(null)!!
            val fallbackInfo = ArgumentTypeInfos.byClass(StringArgumentType.greedyString())
            byClass.javaClass.getMethod("putIfAbsent", Any::class.java, Any::class.java)
                .invoke(byClass, BareTokenArgumentType::class.java, fallbackInfo)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/**
 * Registers [BareTokenArgumentType]'s sync via reflection to alias it to [StringArgumentType].
 * This avoids registering a custom ID in `COMMAND_ARGUMENT_TYPE`, which would kick vanilla clients.
 */
@NeoForgeOnly
object NeoForgeBareTokenArgumentType {
    private var registered = false

    /** Idempotent; call once, early, from mod init. */
    fun register() {
        if (registered) return
        registered = true
        try {
            val field = ArgumentTypeInfos::class.java.getDeclaredField("BY_CLASS")
            field.isAccessible = true
            val byClass = field.get(null)!!
            val fallbackInfo = ArgumentTypeInfos.byClass(StringArgumentType.greedyString())
            byClass.javaClass.getMethod("putIfAbsent", Any::class.java, Any::class.java)
                .invoke(byClass, BareTokenArgumentType::class.java, fallbackInfo)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
