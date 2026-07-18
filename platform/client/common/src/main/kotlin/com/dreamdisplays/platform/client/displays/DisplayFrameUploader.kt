package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.platform.client.render.DisplayTextureResource
import com.dreamdisplays.platform.client.render.DisplayYuvRenderTypes
import com.dreamdisplays.platform.client.render.GpuTextureHandle
import net.minecraft.client.renderer.texture.AbstractTexture
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Render-thread bridge that pushes the media player's decoded frames into a display's GPU textures.
 * Owns only the shader-pack YUV fallback latch; everything else is passed in per call, so it holds no
 * reference to the [DisplayScreen] and never touches its private state.
 *
 * Pulled out of [DisplayScreen] so the screen no longer mixes per-frame upload + quality-handoff
 * promotion with playback and sync state.
 */
internal class DisplayFrameUploader(private val uuid: UUID) {

    /**
     * A YUV texture is live but the active shader pack disabled the YUV render path; restart the
     * pipeline once into RGBA. Latched so the restart is requested only once per fallback.
     */
    private var shaderPackYuvFallbackRequested = false

    /**
     * Uploads the latest decoded frame(s) from [mp] into [tex], invoking [onRendered] whenever a frame
     * actually lands. Promotes a staged quality-handoff texture the instant its first frame arrives, so
     * the picture snaps to the new quality with no freeze and no blank. Render thread only.
     */
    fun upload(mp: MediaPlayer, tex: DisplayTextureResource, onRendered: () -> Unit) {
        if (tex.isYuv && !DisplayYuvRenderTypes.active) {
            if (!shaderPackYuvFallbackRequested) {
                shaderPackYuvFallbackRequested = true
                mp.restartVideoPipeline()
            }
            return
        }
        if (!tex.isYuv) shaderPackYuvFallbackRequested = false

        runCatching {
            if (uploadLive(mp, tex)) onRendered()

            // Quality switch: the new resolution warms up in a parallel channel. Feed its frames into
            // the staged texture; the instant the first one lands, promote channel and texture together
            // so the picture snaps to the new quality with no freeze and no blank.
            val canPromote = tex.hasPending && mp.hasIncomingVideo() && uploadIncoming(mp, tex)
            if (canPromote) {
                if (mp.promoteIncomingVideo()) {
                    tex.promotePending()
                    onRendered()
                    if (MediaPlayer.DEBUG) logger.debug(
                        "{} promoted quality handoff texture {} x {}.",
                        uuid,
                        tex.width,
                        tex.height
                    )
                } else {
                    if (MediaPlayer.DEBUG) logger.debug(
                        "{} discarded staged quality handoff after incoming abort.",
                        uuid
                    )
                    tex.discardPendingAsync()
                }
            }
        }.onFailure { e ->
            logger.warn("$uuid fitTexture failed: ${e.message ?: e::class.java.name}")
        }
    }

    /** Uploads the live channel's ready frame into the current texture(s). Returns true when uploaded. */
    private fun uploadLive(mp: MediaPlayer, tex: DisplayTextureResource): Boolean {
        val w = tex.width
        val h = tex.height
        return if (tex.isYuv) {
            val y = tex.yPlane ?: return false
            val u = tex.uPlane ?: return false
            val v = tex.vPlane ?: return false
            mp.updateFramePlanar(
                gpuHandle(y, w, h),
                gpuHandle(u, (w + 1) / 2, (h + 1) / 2),
                gpuHandle(v, (w + 1) / 2, (h + 1) / 2),
                w,
                h
            )
        } else {
            val texture = tex.texture ?: return false
            mp.updateFrame(gpuHandle(texture, w, h), w, h)
        }
    }

    /** Uploads the incoming (quality-switch) channel's ready frame into the staged texture(s). */
    private fun uploadIncoming(mp: MediaPlayer, tex: DisplayTextureResource): Boolean {
        val w = tex.pendingWidth
        val h = tex.pendingHeight
        return if (tex.isYuv) {
            val y = tex.pendingYPlane ?: return false
            val u = tex.pendingUPlane ?: return false
            val v = tex.pendingVPlane ?: return false
            mp.updateIncomingFramePlanar(
                gpuHandle(y, w, h),
                gpuHandle(u, (w + 1) / 2, (h + 1) / 2),
                gpuHandle(v, (w + 1) / 2, (h + 1) / 2),
                w,
                h
            )
        } else {
            val texture = tex.pendingTexture ?: return false
            mp.updateIncomingFrame(gpuHandle(texture, w, h), w, h)
        }
    }

    private fun gpuHandle(texture: AbstractTexture, width: Int, height: Int): GpuTextureHandle {
        //? if >=1.21.11 {
        return GpuTextureHandle(texture.getTexture())
        //?} else
        /*return GpuTextureHandle(texture.getId(), width, height)*/
    }

    companion object {
        /** Logger for upload failures and quality-handoff diagnostics. */
        private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayFrameUploader")
    }
}
