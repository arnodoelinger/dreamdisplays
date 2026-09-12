package com.dreamdisplays.media.source.direct

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * For a host nobody vouched for, this sniff is the only evidence that a link really serves media:
 * the `Content-Type` header is whatever that host decided to write, and the URL ending in `.mp4`
 * means nothing at all.
 */
class DirectMediaProbeTest {
    private fun bytes(vararg values: Int) = ByteArray(64) { i -> values.getOrElse(i) { 0 }.toByte() }
    private fun text(value: String) = value.toByteArray(StandardCharsets.US_ASCII).copyOf(64)

    @Test
    fun `mainstream containers are recognized from their leading bytes`() {
        val mp4 = ByteArray(64).also {
            "ftyp".toByteArray(StandardCharsets.US_ASCII).copyInto(it, 4)
        }
        assertEquals("video/mp4", DirectMediaProbe.magicContentType(mp4))
        assertEquals("video/webm", DirectMediaProbe.magicContentType(bytes(0x1A, 0x45, 0xDF, 0xA3)))
        assertEquals("video/x-flv", DirectMediaProbe.magicContentType(text("FLV")))
        assertEquals("video/ogg", DirectMediaProbe.magicContentType(text("OggS")))
        assertEquals("video/x-ms-asf", DirectMediaProbe.magicContentType(bytes(0x30, 0x26, 0xB2, 0x75)))
        assertEquals("video/mpeg", DirectMediaProbe.magicContentType(bytes(0x00, 0x00, 0x01, 0xBA)))
        val ts = ByteArray(512).also { it[0] = 0x47; it[188] = 0x47 }
        assertEquals("video/mp2t", DirectMediaProbe.magicContentType(ts))
    }

    @Test
    fun `avi is only recognized as RIFF carrying AVI`() {
        val avi = ByteArray(64).also {
            "RIFF".toByteArray(StandardCharsets.US_ASCII).copyInto(it, 0)
            "AVI ".toByteArray(StandardCharsets.US_ASCII).copyInto(it, 8)
        }
        assertEquals("video/x-msvideo", DirectMediaProbe.magicContentType(avi))
        val wav = ByteArray(64).also {
            "RIFF".toByteArray(StandardCharsets.US_ASCII).copyInto(it, 0)
            "WAVE".toByteArray(StandardCharsets.US_ASCII).copyInto(it, 8)
        }
        assertNull(DirectMediaProbe.magicContentType(wav), "A RIFF container that isn't AVI is not video.")
    }

    @Test
    fun `manifests are recognized as text`() {
        assertEquals(
            "application/vnd.apple.mpegurl",
            DirectMediaProbe.magicContentType(text("#EXTM3U\n#EXT-X-VERSION:3\n")),
        )
        assertEquals("application/dash+xml", DirectMediaProbe.magicContentType(text("<MPD xmlns=\"urn:mpeg\">")))
        assertEquals(
            "application/dash+xml",
            DirectMediaProbe.magicContentType(text("<?xml version=\"1.0\"?><MPD xmlns=\"urn\">")),
        )
    }

    @Test
    fun `anything that is not a container stays unrecognized`() {
        assertNull(DirectMediaProbe.magicContentType(text("<!DOCTYPE html><html><head><title>hi")))
        assertNull(DirectMediaProbe.magicContentType(text("{\"error\":\"nope\",\"code\":404,\"x\":1}")))
        assertNull(DirectMediaProbe.magicContentType(text("just some text served as a video file")))
        assertNull(DirectMediaProbe.magicContentType(ByteArray(4)), "Too few bytes to judge.")
        assertNull(
            DirectMediaProbe.magicContentType(text("GET /clip.mp4 HTTP/1.1 served as a body")),
            "A lone 0x47 is the letter G, not a transport stream.",
        )
    }
}
