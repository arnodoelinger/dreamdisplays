package com.dreamdisplays.platform.server.registrar

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
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
 * Warning: `register()` (see [FabricBareTokenArgumentType] / [NeoForgeBareTokenArgumentType]) must
 * run from `Server.onInitialize()`.
 */
object BareTokenArgumentType : ArgumentType<String> {
    private val MISSING = SimpleCommandExceptionType(LiteralMessage("Expected a value."))
    val ID: Identifier = Identifier.fromNamespaceAndPath("dreamdisplays", "bare_token")

    override fun parse(reader: StringReader): String {
        val start = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        if (reader.cursor == start) throw MISSING.create()
        return reader.string.substring(start, reader.cursor)
    }
}

/**
 * Registers [BareTokenArgumentType]'s sync info via `Fabric` API's public `ArgumentTypeRegistry`.
 */
@FabricOnly
object FabricBareTokenArgumentType {
    private var registered = false

    /** Idempotent; call once, early, from mod init. */
    fun register() {
        if (registered) return
        registered = true
        val info = SingletonArgumentInfo.contextFree { BareTokenArgumentType }
        net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry.registerArgumentType(
            BareTokenArgumentType.ID, BareTokenArgumentType::class.java, info,
        )
    }
}

/**
 * Registers [BareTokenArgumentType]'s sync info by reflectively populating
 * `ArgumentTypeInfos.BY_CLASS`. `NeoForge` actually patches a public `registerByClass` method onto
 * that class at runtime for exactly this purpose, but the patch isn't present on the `universal`
 * jar this module compiles against (only applied by `NeoForge`'s installer to the game jar), so it
 * can't be referenced statically here. Unlike `Fabric`, `NeoForge` ships mods built directly
 * against Mojang's official names with no separate remap-to-obfuscated step, so — unlike the
 * `Fabric` case this replaced — this field-name string literal does resolve correctly at runtime.
 */
@NeoForgeOnly
object NeoForgeBareTokenArgumentType {
    private var registered = false

    /** Idempotent; call once, early, from mod init. */
    fun register() {
        if (registered) return
        registered = true
        val field = net.minecraft.commands.synchronization.ArgumentTypeInfos::class.java.getDeclaredField("BY_CLASS")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val byClass = field.get(null) as MutableMap<Class<*>, net.minecraft.commands.synchronization.ArgumentTypeInfo<*, *>>
        val info = SingletonArgumentInfo.contextFree { BareTokenArgumentType }
        byClass.putIfAbsent(BareTokenArgumentType::class.java, info)
        Registry.register(BuiltInRegistries.COMMAND_ARGUMENT_TYPE, BareTokenArgumentType.ID, info)
    }
}
