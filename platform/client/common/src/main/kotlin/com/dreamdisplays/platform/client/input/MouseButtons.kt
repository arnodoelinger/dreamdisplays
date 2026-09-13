package com.dreamdisplays.platform.client.input

import net.minecraft.client.Minecraft
//? if >=26.3 {
import com.mojang.blaze3d.platform.InputConstants
import org.lwjgl.sdl.SDLMouse
import org.lwjgl.system.MemoryStack
//?} else
/*import org.lwjgl.glfw.GLFW*/

/** Mouse-button indices: GLFW used 0 / 1 for left / right; 26.3 SDL uses 1 / 3. */
object MouseButtons {
    fun isLeft(button: Int): Boolean =
        //? if >=26.3 {
        button == InputConstants.MOUSE_BUTTON_LEFT
        //?} else
        /*button == 0*/

    fun isRight(button: Int): Boolean =
        //? if >=26.3 {
        button == InputConstants.MOUSE_BUTTON_RIGHT
        //?} else
        /*button == 1*/

    /** Hardware left-button state. */
    fun hardwareLeftDown(): Boolean =
        //? if >=26.3 {
        (sdlButtonMask() and SDLMouse.SDL_BUTTON_LMASK) != 0
        //?} else
        /*glfwDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)*/

    /** Hardware right-button state. See [hardwareLeftDown]. */
    fun hardwareRightDown(): Boolean =
        //? if >=26.3 {
        (sdlButtonMask() and SDLMouse.SDL_BUTTON_RMASK) != 0
        //?} else
        /*glfwDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT)*/

    //? if >=26.3 {
    private fun sdlButtonMask(): Int = MemoryStack.stackPush().use { stack ->
        SDLMouse.SDL_GetMouseState(stack.mallocFloat(1), stack.mallocFloat(1))
    }
    //?} else
    /*private fun glfwDown(button: Int): Boolean {
        val mc = Minecraft.getInstance()
        val window =
            //? if >=1.21.11 {
            mc.window.handle()
            //?}
            //? if <1.21.11 {
            mc.window.window
            //?}
        return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS
    }*/
}
