package com.dreamdisplays.platform.client.render

import com.mojang.blaze3d.systems.RenderSystem

/** Small runtime probes for renderer backends that replace or virtualize OpenGL. */
internal object RenderBackendCompat {
    /** True when the `VulkanMod` renderer replacement is installed. */
    val isVulkanModLoaded: Boolean by lazy {
        isFabricModLoaded("vulkanmod") || isNeoForgeModLoaded("vulkanmod")
    }

    /** True when raw OpenGL calls are safe (real GL backend and no Vulkan replacement). */
    fun canUseDirectOpenGl(): Boolean = isOpenGlBackend() && !isVulkanModLoaded

    /** Best-effort name of the active render backend (`opengl` / `vulkan` / `vulkanmod` / `other`). */
    fun backendName(): String = runCatching {
        val deviceClass = RenderSystem.getDevice().javaClass.name.lowercase()
        when {
            isVulkanModLoaded -> "vulkanmod"
            "vulkan" in deviceClass -> "vulkan"
            ".opengl." in deviceClass || deviceClass.substringAfterLast('.').startsWith("gl") -> "opengl"
            else -> "other"
        }
    }.getOrDefault("unknown")

    /** Name of the texture-upload path taken for this backend (direct PBO vs. command encoder). */
    fun textureUploadPath(): String =
        if (canUseDirectOpenGl()) "direct_opengl_pbo" else "command_encoder"

    /** True when the active render device is a real OpenGL backend. */
    fun isOpenGlBackend(): Boolean {
        val deviceClass = RenderSystem.getDevice().javaClass.name.lowercase()
        return ".opengl." in deviceClass || deviceClass.substringAfterLast('.').startsWith("gl")
    }

    /** True if the Fabric mod [id] is loaded. */
    private fun isFabricModLoaded(id: String): Boolean = runCatching {
        val loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader")
        val loader = loaderClass.getMethod("getInstance").invoke(null)
        loaderClass.getMethod("isModLoaded", String::class.java).invoke(loader, id) as Boolean
    }.getOrDefault(false)

    /** True if the NeoForge mod [id] is loaded. */
    private fun isNeoForgeModLoaded(id: String): Boolean = runCatching {
        val modListClass = Class.forName("net.neoforged.fml.ModList")
        val modList = modListClass.getMethod("get").invoke(null)
        modListClass.getMethod("isLoaded", String::class.java).invoke(modList, id) as Boolean
    }.getOrDefault(false)
}
