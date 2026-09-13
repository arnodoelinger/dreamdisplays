package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.util.asJsonObjectOrNull
import com.dreamdisplays.util.json.DreamJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeChapterParseTest {

    /** Parses [json] the way [YouTubeInnerTube.metadata] does before handing it to the chapter reader. */
    private fun chaptersOf(json: String) =
        YouTubeInnerTube.extractChapters(DreamJson.compact.parseToJsonElement(json).asJsonObjectOrNull()!!)

    @Test
    fun readsChaptersFromMacroMarkersPanel() {
        val chapters = chaptersOf(
            """
            {
              "engagementPanels": [
                { "engagementPanelSectionListRenderer": { "content": { "somethingElse": {} } } },
                {
                  "engagementPanelSectionListRenderer": {
                    "content": {
                      "macroMarkersListRenderer": {
                        "contents": [
                          { "macroMarkersInfoItemRenderer": { "label": { "simpleText": "not a chapter" } } },
                          {
                            "macroMarkersListItemRenderer": {
                              "title": { "simpleText": "Introduction" },
                              "timeDescription": { "simpleText": "0:00" },
                              "onTap": { "watchEndpoint": { "startTimeSeconds": 0 } }
                            }
                          },
                          {
                            "macroMarkersListItemRenderer": {
                              "title": { "simpleText": "What you will get" },
                              "timeDescription": { "simpleText": "0:24" },
                              "onTap": { "watchEndpoint": { "startTimeSeconds": 24 } }
                            }
                          },
                          {
                            "macroMarkersListItemRenderer": {
                              "title": { "simpleText": "Practice" },
                              "timeDescription": { "simpleText": "2:33" },
                              "onTap": { "watchEndpoint": { "startTimeSeconds": 153 } }
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, chapters.size)
        assertEquals("Introduction", chapters[0].title)
        assertEquals(0L, chapters[0].startSeconds)
        assertEquals("What you will get", chapters[1].title)
        assertEquals(24L, chapters[1].startSeconds)
        assertEquals("Practice", chapters[2].title)
        assertEquals(153L, chapters[2].startSeconds)
    }

    @Test
    fun fallsBackToTimeDescriptionWhenEndpointHasNoStart() {
        val chapters = chaptersOf(
            """
            {
              "engagementPanels": [{
                "engagementPanelSectionListRenderer": { "content": { "macroMarkersListRenderer": { "contents": [
                  {
                    "macroMarkersListItemRenderer": {
                      "title": { "runs": [{ "text": "Chapter via runs" }] },
                      "timeDescription": { "simpleText": "1:05:30" }
                    }
                  }
                ] } } }
              }]
            }
            """.trimIndent(),
        )

        assertEquals(1, chapters.size)
        assertEquals("Chapter via runs", chapters[0].title)
        assertEquals(3930L, chapters[0].startSeconds)
    }

    @Test
    fun reportsNoChaptersForAVideoWithoutThem() {
        val chapters = chaptersOf(
            """
            {
              "engagementPanels": [
                { "engagementPanelSectionListRenderer": { "content": { "structuredDescriptionContentRenderer": {} } } }
              ]
            }
            """.trimIndent(),
        )
        assertTrue(chapters.isEmpty())
    }

    @Test
    fun reportsNoChaptersWhenTheResponseHasNoPanels() {
        assertTrue(chaptersOf("""{ "contents": {} }""").isEmpty())
    }
}
