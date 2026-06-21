package com.dreamdisplays.api.render

interface RenderContext {
    val tickDelta: Float
    val cameraX: Double
    val cameraY: Double
    val cameraZ: Double
}
