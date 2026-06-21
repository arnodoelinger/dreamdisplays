package com.dreamdisplays.api.platform

interface Platform {
    val id: String
    val side: PlatformSide
    val minecraftVersion: String
    val modVersion: String
    val scheduler: PlatformScheduler
    val logger: PlatformLogger
    val paths: PlatformPaths
    val isDevEnvironment: Boolean
}
