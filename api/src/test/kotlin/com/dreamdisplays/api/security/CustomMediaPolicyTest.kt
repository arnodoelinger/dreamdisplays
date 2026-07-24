@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api.security

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomMediaPolicyTest {
    private val default = CustomMediaPolicy.Settings.DEFAULT
    private val off = CustomMediaPolicy.Settings(enabled = false)

    @Test
    fun customLinkIsAllowedByDefault() = assertEquals(
        CustomMediaPolicy.Verdict.ALLOWED,
        CustomMediaPolicy.evaluate("https://cdn.example.com/v.mp4", default),
    )

    @Test
    fun customLinkIsRefusedWhenDisabled() = assertEquals(
        CustomMediaPolicy.Verdict.DISABLED,
        CustomMediaPolicy.evaluate("https://cdn.example.com/v.mp4", off),
    )

    @Test
    fun youTubeIsUnaffectedWhenCustomIsDisabled() = assertEquals(
        CustomMediaPolicy.Verdict.ALLOWED,
        CustomMediaPolicy.evaluate("https://www.youtube.com/watch?v=dQw4w9WgXcQ", off),
    )

    @Test
    fun twitchIsUnaffectedWhenCustomIsDisabled() = assertEquals(
        CustomMediaPolicy.Verdict.ALLOWED,
        CustomMediaPolicy.evaluate("https://www.twitch.tv/someone", off),
    )

    @Test
    fun blankUrlIsAlwaysAllowed() {
        assertEquals(CustomMediaPolicy.Verdict.ALLOWED, CustomMediaPolicy.evaluate("", off))
        assertFalse(CustomMediaPolicy.isCustom(""))
    }

    @Test
    fun blockedHostIsRefused() = assertEquals(
        CustomMediaPolicy.Verdict.HOST_BLOCKED,
        CustomMediaPolicy.evaluate(
            "https://bad.example/v.mp4",
            CustomMediaPolicy.Settings(blockedHosts = listOf("bad.example")),
        ),
    )

    @Test
    fun blockRuleCoversSubdomains() = assertEquals(
        CustomMediaPolicy.Verdict.HOST_BLOCKED,
        CustomMediaPolicy.evaluate(
            "https://cdn.bad.example/v.mp4",
            CustomMediaPolicy.Settings(blockedHosts = listOf("bad.example")),
        ),
    )

    @Test
    fun wildcardRuleIsAccepted() = assertEquals(
        CustomMediaPolicy.Verdict.HOST_BLOCKED,
        CustomMediaPolicy.evaluate(
            "https://cdn.bad.example/v.mp4",
            CustomMediaPolicy.Settings(blockedHosts = listOf("*.bad.example")),
        ),
    )

    @Test
    fun allowlistRefusesEverythingElse() {
        val settings = CustomMediaPolicy.Settings(allowedHosts = listOf("files.myserver.net"))
        assertEquals(
            CustomMediaPolicy.Verdict.ALLOWED,
            CustomMediaPolicy.evaluate("https://files.myserver.net/v.mp4", settings),
        )
        assertEquals(
            CustomMediaPolicy.Verdict.HOST_NOT_ALLOWED,
            CustomMediaPolicy.evaluate("https://elsewhere.example/v.mp4", settings),
        )
    }

    @Test
    fun blocklistWinsOverAllowlist() = assertEquals(
        CustomMediaPolicy.Verdict.HOST_BLOCKED,
        CustomMediaPolicy.evaluate(
            "https://files.example/v.mp4",
            CustomMediaPolicy.Settings(
                allowedHosts = listOf("files.example"),
                blockedHosts = listOf("files.example"),
            ),
        ),
    )

    @Test
    fun customDetectionSeparatesPlatformsFromLinks() {
        assertFalse(CustomMediaPolicy.isCustom("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(CustomMediaPolicy.isCustom("https://clips.twitch.tv/SomeClipSlug"))
        assertFalse(CustomMediaPolicy.isCustom("https://vimeo.com/123456789"))
        assertFalse(CustomMediaPolicy.isCustom("https://kick.com/xqc"))
        assertTrue(CustomMediaPolicy.isCustom("https://cdn.example.com/v.mp4"))
    }

    @Test
    fun disablingCustomDoesNotBlockVimeoOrKick() {
        assertEquals(CustomMediaPolicy.Verdict.ALLOWED, CustomMediaPolicy.evaluate("https://vimeo.com/123456789", off))
        assertEquals(CustomMediaPolicy.Verdict.ALLOWED, CustomMediaPolicy.evaluate("https://kick.com/xqc", off))
    }
}
