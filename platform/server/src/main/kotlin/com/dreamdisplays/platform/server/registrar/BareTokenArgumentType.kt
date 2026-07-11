package com.dreamdisplays.platform.server.registrar

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import net.minecraft.commands.synchronization.ArgumentTypeInfo
import net.minecraft.commands.synchronization.ArgumentTypeInfos
import net.minecraft.commands.synchronization.SingletonArgumentInfo
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * Single space-delimited token, unquoted, for `Fabric` / `NeoForge`'s raw vanilla command tree
 * (`VanillaCommandTree.kt`; `Paper` uses a `CustomArgumentType` wrapper instead — see
 * `CommandRegistrar.kt` — since `Paper` substitutes an already-registered native type when building
 * the per-player sync packet, so it never needs any of this). `Brigadier`'s stock
 * `StringArgumentType.word()`/`.string()` only allow `@` / `%` / `:` / `/` etc. when the whole token is
 * quoted; this type just reads up to the next space so selectors (`@a`, `%group`) and raw URLs work
 * bare. Used for `/display fullscreen`'s `target` (player / selector list) and `id` (display id or
 * video URL) arguments.
 *
 * Warning: [register] must run from `Server.onInitialize()`.
 */
object BareTokenArgumentType : ArgumentType<String> {
    private val MISSING = SimpleCommandExceptionType(LiteralMessage("Expected a value."))
    private val ID = Identifier.fromNamespaceAndPath("dreamdisplays", "bare_token")
    private var registered = false

    /** Registers this type with vanilla's `ArgumentTypeInfos`/`BuiltInRegistries`. Idempotent; call once, early, from mod init. */
    fun register() {
        if (registered) return
        registered = true
        val field = ArgumentTypeInfos::class.java.getDeclaredField("BY_CLASS")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val byClass = field.get(null) as MutableMap<Class<*>, ArgumentTypeInfo<*, *>>
        val info = SingletonArgumentInfo.contextFree { BareTokenArgumentType }
        byClass.putIfAbsent(BareTokenArgumentType::class.java, info)
        Registry.register(BuiltInRegistries.COMMAND_ARGUMENT_TYPE, ID, info)
    }

    override fun parse(reader: StringReader): String {
        val start = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        if (reader.cursor == start) throw MISSING.create()
        return reader.string.substring(start, reader.cursor)
    }
}
