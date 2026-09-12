package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.Initializer
import com.mojang.blaze3d.platform.NativeImage
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType
import net.minecraft.util.FastColor*/
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rasterizes the current subtitle line into a small opaque GPU texture (a dark rounded-ish box
 * behind white text, sized to the wrapped text itself) that [ScreenRenderer] draws as a quad over
 * the bottom of the display.
 *
 * Regenerated only when the text changes. Rendered with plain AWT text layout.
 */
class SubtitleOverlayTexture {
    private var lastText: String? = null
    private var identifier: Identifier? = null
    private var texture: DynamicTexture? = null
    private var cachedRenderType: RenderType? = null

    var aspectRatio: Float = 1f
        private set

    /** Updates the texture if [text] differs from what's currently baked. Render thread only. */
    fun update(text: String?) {
        val normalized = text?.takeIf { it.isNotBlank() }
        if (normalized == lastText) return
        lastText = normalized

        texture?.close()
        texture = null
        cachedRenderType = null
        if (normalized == null) return

        val image = rasterize(normalized)
        aspectRatio = image.width.toFloat() / image.height.toFloat()
        val native = toNativeImage(image)
        val id = identifier ?: Identifier.fromNamespaceAndPath(
            Initializer.MOD_ID,
            "dynamic/subtitle_${INSTANCE_ID.incrementAndGet()}",
        ).also { identifier = it }

        val dynamic =
            //? if >=1.21.11 {
            DynamicTexture({ "dreamdisplays-subtitle" }, native)
        //?} else
        /*DynamicTexture(native)*/
        dynamic.upload()
        Minecraft.getInstance().textureManager.register(id, dynamic)
        texture = dynamic
    }

    /** True while a cue is currently baked and ready to draw. */
    @Suppress("UNUSED")
    fun hasContent(): Boolean = texture != null

    /** The unlit [RenderType] sampling the current texture, or null when there's nothing to show. */
    fun renderType(): RenderType? {
        val id = identifier ?: return null
        if (texture == null) return null
        return cachedRenderType ?: DisplayUnlitRenderTypes.create("dream-displays-subtitle", id).also { cachedRenderType = it }
    }

    /** Releases the GPU texture. Call once when the owning display is unregistered. */
    fun dispose() {
        texture?.close()
        texture = null
        cachedRenderType = null
        lastText = null
    }

    private fun rasterize(text: String): BufferedImage {
        val font = Font(Font.SANS_SERIF, Font.BOLD, FONT_SIZE_PX)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val metrics = probe.createGraphics().let { g ->
            g.font = font
            g.fontMetrics.also { g.dispose() }
        }

        val lines = wrap(text, metrics)
        val lineHeight = metrics.height
        val textWidth = lines.maxOf { metrics.stringWidth(it) }
        val width = (textWidth + PADDING_X * 2).coerceAtLeast(1)
        val height = (lineHeight * lines.size + PADDING_Y * 2).coerceAtLeast(1)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = BACKGROUND_COLOR
        g.fillRect(0, 0, width, height)
        g.font = font
        g.color = Color.WHITE
        var y = PADDING_Y + metrics.ascent
        for (line in lines) {
            val lineWidth = metrics.stringWidth(line)
            g.drawString(line, (width - lineWidth) / 2, y)
            y += lineHeight
        }
        g.dispose()
        return image
    }

    private fun wrap(text: String, metrics: FontMetrics): List<String> {
        val out = ArrayList<String>()
        for (rawLine in text.split('\n')) {
            var current = StringBuilder()
            for (word in rawLine.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (metrics.stringWidth(candidate) > MAX_TEXT_WIDTH_PX && current.isNotEmpty()) {
                    out.add(current.toString())
                    current = StringBuilder(word)
                } else {
                    current = StringBuilder(candidate)
                }
            }
            if (current.isNotEmpty()) out.add(current.toString())
        }
        return out.ifEmpty { listOf("") }.take(MAX_LINES)
    }

    private fun toNativeImage(image: BufferedImage): NativeImage {
        val w = image.width
        val h = image.height
        val native = NativeImage(NativeImage.Format.RGBA, w, h, false)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = image.getRGB(x, y)
                //? if >=1.21.11 {
                native.setPixel(x, y, argb)
                //?} else
                /*val a = (argb ushr 24) and 0xFF
                val r = (argb ushr 16) and 0xFF
                val gr = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                native.setPixelRGBA(x, y, FastColor.ABGR32.color(a, r, gr, b))*/
            }
        }
        return native
    }

    companion object {
        private val INSTANCE_ID = AtomicInteger(0)
        private const val FONT_SIZE_PX = 34
        private const val PADDING_X = 18
        private const val PADDING_Y = 10
        private const val MAX_TEXT_WIDTH_PX = 900
        private const val MAX_LINES = 3
        private val BACKGROUND_COLOR = Color(0, 0, 0)
    }
}
