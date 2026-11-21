package com.dreamdisplays.media.player.process

import java.io.File
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibvaCompatTest {
    private fun library(dynstr: String): File =
        createTempFile("libva", ".so.2").toFile().apply {
            deleteOnExit()
            writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0) + dynstr.toByteArray())
        }

    @Test
    fun `libva 2_21 exports vaMapBuffer2`() {
        assertTrue(LibvaCompat.exports(library("vaMapBuffer\u0000vaMapBuffer2\u0000vaUnmapBuffer\u0000"), "vaMapBuffer2"))
    }

    @Test
    fun `libva 2_20 lacks vaMapBuffer2`() {
        assertFalse(LibvaCompat.exports(library("vaMapBuffer\u0000vaUnmapBuffer\u0000"), "vaMapBuffer2"))
    }

    @Test
    fun `a longer name ending in the symbol does not count`() {
        assertFalse(LibvaCompat.exports(library("my_vaMapBuffer2\u0000"), "vaMapBuffer2"))
    }
}
