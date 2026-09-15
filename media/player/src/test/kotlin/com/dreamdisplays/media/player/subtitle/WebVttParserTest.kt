package com.dreamdisplays.media.player.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebVttParserTest {
    private val ms = 1_000_000L

    private fun textAt(cues: List<SubtitleCue>, millis: Long): String? = WebVttParser.cueAt(cues, millis * ms)?.text

    @Test
    fun `nbsp entities and filler lines are not shown`() {
        val cues = WebVttParser.parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            Hello&nbsp;world&nbsp;
            &nbsp;
            """.trimIndent()
        )
        assertEquals("Hello world", textAt(cues, 2_000))
    }

    @Test
    fun `numeric and named entities are decoded`() {
        val cues = WebVttParser.parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            Tom &amp; Jerry&#39;s &#x2019;show&#x2019; &lt;live&gt;
            """.trimIndent()
        )
        assertEquals("Tom & Jerry's ’show’ <live>", textAt(cues, 2_000))
    }

    @Test
    fun `youtube rolling captions switch lines without a blank or a stub`() {
        val cues = WebVttParser.parse(
            """
            WEBVTT
            Kind: captions

            00:00:01.000 --> 00:00:03.500 align:start position:0%
            first line
            second<00:00:02.000><c> line</c>

            00:00:03.500 --> 00:00:03.510 align:start position:0%
            second line


            00:00:03.510 --> 00:00:06.000 align:start position:0%
            second line
            third<00:00:04.000><c> line</c>
            """.trimIndent()
        )
        assertEquals("first line\nsecond line", textAt(cues, 3_499))
        assertEquals("first line\nsecond line", textAt(cues, 3_505))
        assertEquals("second line\nthird line", textAt(cues, 3_510))
    }

    @Test
    fun `short gaps are bridged but long pauses stay blank`() {
        val cues = WebVttParser.parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            one

            00:00:02.100 --> 00:00:03.000
            two

            00:00:05.000 --> 00:00:06.000
            three
            """.trimIndent()
        )
        assertEquals("one", textAt(cues, 2_050))
        assertNull(textAt(cues, 4_000))
        assertEquals("three", textAt(cues, 5_500))
    }

    @Test
    fun `overlapping cues hand over to the newer one`() {
        val cues = WebVttParser.parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:04.000
            older

            00:00:02.000 --> 00:00:05.000
            newer
            """.trimIndent()
        )
        assertEquals("older", textAt(cues, 1_500))
        assertEquals("newer", textAt(cues, 3_000))
    }
}
