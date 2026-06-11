package com.dreamdisplays.render.api

interface RenderContext {
    val tickDelta: Float
    val cameraX: Double
    val cameraY: Double
    val cameraZ: Double
}
