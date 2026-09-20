package com.dreamdisplays.platform.client.render

//? if >=1.21.11 <26.3 {
import com.mojang.blaze3d.opengl.GlStateManager
//?}
//? if <1.21.11 {
import com.mojang.blaze3d.platform.GlStateManager
//?}
import org.lwjgl.opengl.GL11

/**
 * Binds a `GL_TEXTURE_2D` without desynchronising Minecraft's texture binding cache.
 *
 * `GlStateManager` remembers which texture is bound to every texture unit and skips the actual
 * `glBindTexture` call when its cached binding already equals the requested id. Any raw
 * `glBindTexture` (or `glDeleteTextures` of a texture that is currently bound) breaks that
 * assumption, and a later bind issued through the state manager can then be dropped completely,
 * leaving *no* texture bound at all.
 *
 * That is fatal when the driver runs with error checking disabled
 * (`EGL_KHR_create_context_no_error`, i.e. `MESA_NO_ERROR`): `glTexSubImage2D` reaches Mesa's
 * unchecked entry point, which dereferences the missing texture image and kills the JVM with
 * SIGSEGV (seen in `texture_sub_image()` in Mesa's `teximage.c`).
 *
 * Passing [id] through the cache twice - 0 first, then [id] - guarantees that a real
 * `glBindTexture` is issued while keeping `GlStateManager` accurate.
 *
 * @param stateCache whether the current render path shares Minecraft's `GlStateManager` state
 * @param id the `GL_TEXTURE_2D` name to bind, or `0` to unbind
 */
internal fun bindTexture2D(stateCache: Boolean, id: Int) {
    if (!stateCache) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        return
    }
    GlStateManager._bindTexture(0)
    GlStateManager._bindTexture(id)
}
