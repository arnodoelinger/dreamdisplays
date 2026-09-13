package com.dreamdisplays.platform.server

import io.github.arnodoelinger.platformweaver.PlatformOnly

/**
 * Marks a declaration shared between `Fabric` and `NeoForge` — the two modded loaders that both
 * compile against vanilla / Mojang-mapped types (e.g. [net.minecraft.server.level.ServerPlayer]).
 * Stripped on `paper` and on any proxy target.
 */
@PlatformOnly("fabric", "neoforge")
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.SOURCE)
annotation class ModLoaderOnly
