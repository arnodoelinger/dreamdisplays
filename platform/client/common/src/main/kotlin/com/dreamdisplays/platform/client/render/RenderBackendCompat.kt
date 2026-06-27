package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.render.RenderBackend
import com.dreamdisplays.api.render.TextureUploadPath
import com.mojang.blaze3d.systems.RenderSystem

/** Small runtime probes for renderer backends that replace or virtualize OpenGL. */
internal object RenderBackendCompat {
    /** True when the `VulkanMod` renderer replacement is installed. */
    val isVulkanModLoaded: Boolean by lazy {
        isFabricModLoaded("vulkanmod") || isNeoForgeModLoaded("vulkanmod")
    }

    /** True when raw OpenGL calls are safe (real GL backend and no Vulkan replacement). */
    fun canUseDirectOpenGl(): Boolean = isOpenGlBackend() && !isVulkanModLoaded

    /** Best-effort typed active render backend. */
    fun backend(): RenderBackend = runCatching {
        val deviceClass = RenderSystem.getDevice().javaClass.name.lowercase()
        when {
            isVulkanModLoaded -> RenderBackend.VULKAN_MOD
            "vulkan" in deviceClass -> RenderBackend.VULKAN
            ".opengl." in deviceClass || deviceClass.substringAfterLast('.').startsWith("gl") -> RenderBackend.OPENGL
            else -> RenderBackend.OTHER
        }
    }.getOrDefault(RenderBackend.UNKNOWN)

    /** Texture-upload path taken for this backend (direct PBO vs. command encoder). */
    fun textureUploadPath(): TextureUploadPath =
        if (canUseDirectOpenGl()) TextureUploadPath.DIRECT_OPENGL_PBO else TextureUploadPath.COMMAND_ENCODER

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
