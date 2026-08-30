package com.germanstreams

import com.germanstreams.common.SourceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceLanguageTest {

    @Test
    fun `recognises the labels serienstream actually serves`() {
        // Verified against the live episode page: these three strings, no others.
        assertEquals(SourceLanguage.GermanDub, SourceLanguage.fromLabel("Deutsch"))
        assertEquals(SourceLanguage.GermanSub, SourceLanguage.fromLabel("Ger-Sub"))
        assertEquals(SourceLanguage.EnglishSub, SourceLanguage.fromLabel("Englisch"))
    }

    @Test
    fun `recognises the spelled-out forms the older markup used`() {
        assertEquals(SourceLanguage.GermanSub, SourceLanguage.fromLabel("Deutsch (Untertitel)"))
        assertEquals(SourceLanguage.EnglishSub, SourceLanguage.fromLabel("Englisch (Untertitel)"))
        assertEquals(SourceLanguage.GermanDub, SourceLanguage.fromLabel("German Dub"))
    }

    @Test
    fun `maps the flag classes on episode rows`() {
        assertEquals(SourceLanguage.GermanDub, SourceLanguage.fromFlagClass("german"))
        assertEquals(SourceLanguage.GermanSub, SourceLanguage.fromFlagClass("english-german"))
        assertEquals(SourceLanguage.EnglishSub, SourceLanguage.fromFlagClass("english"))
        assertEquals(SourceLanguage.Unknown, SourceLanguage.fromFlagClass("klingon"))
    }

    @Test
    fun `maps aniworld numeric keys`() {
        assertEquals(SourceLanguage.GermanDub, SourceLanguage.fromAniWorldKey("1"))
        assertEquals(SourceLanguage.EnglishSub, SourceLanguage.fromAniWorldKey("2"))
        assertEquals(SourceLanguage.GermanSub, SourceLanguage.fromAniWorldKey("3"))
        assertEquals(SourceLanguage.Unknown, SourceLanguage.fromAniWorldKey(""))
    }

    @Test
    fun `german dub outranks german sub outranks english`() {
        val ordered = listOf(
            SourceLanguage.EnglishSub,
            SourceLanguage.GermanDub,
            SourceLanguage.Unknown,
            SourceLanguage.GermanSub,
        ).sortedBy { it.priority }
        assertEquals(
            listOf(
                SourceLanguage.GermanDub,
                SourceLanguage.GermanSub,
                SourceLanguage.EnglishSub,
                SourceLanguage.Unknown,
            ),
            ordered,
        )
    }
}
