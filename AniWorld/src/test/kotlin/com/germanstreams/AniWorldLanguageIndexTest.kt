package com.germanstreams

import com.germanstreams.common.AniWorldLanguageIndex
import com.germanstreams.common.SourceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AniWorldLanguageIndexTest {
    @Test fun `cold index is unknown without a network lookup`() {
        AniWorldLanguageIndex.clear()
        assertEquals(emptySet(), AniWorldLanguageIndex.languages("/anime/stream/one-piece"))
        assertEquals(0, AniWorldLanguageIndex.size())
    }

    @Test fun `feed and season positives merge for one anime`() {
        AniWorldLanguageIndex.clear()
        AniWorldLanguageIndex.observe("/anime/stream/one-piece/staffel-22/episode-35", setOf(SourceLanguage.GermanSub))
        AniWorldLanguageIndex.observe("https://aniworld.to/anime/stream/one-piece/staffel-22",
            setOf(SourceLanguage.GermanDub, SourceLanguage.EnglishSub))
        assertEquals(setOf(SourceLanguage.GermanDub, SourceLanguage.GermanSub, SourceLanguage.EnglishSub),
            AniWorldLanguageIndex.languages("/anime/stream/one-piece"))
        assertEquals(1, AniWorldLanguageIndex.size())
    }

    @Test fun `unknown results do not erase positive evidence or add sub automatically`() {
        AniWorldLanguageIndex.clear()
        AniWorldLanguageIndex.observe("/anime/stream/one-piece", setOf(SourceLanguage.GermanDub))
        AniWorldLanguageIndex.observe("/anime/stream/one-piece", emptySet())
        assertEquals(setOf(SourceLanguage.GermanDub), AniWorldLanguageIndex.languages("/anime/stream/one-piece"))
    }

    @Test fun `other anime and invalid paths never mix`() {
        AniWorldLanguageIndex.clear()
        AniWorldLanguageIndex.observe("/anime/stream/one-piece/filme/film-1", setOf(SourceLanguage.EnglishSub))
        AniWorldLanguageIndex.observe("/anime/stream/bleach/staffel-1/episode-1", setOf(SourceLanguage.GermanDub))
        AniWorldLanguageIndex.observe("javascript:/anime/stream/evil", setOf(SourceLanguage.GermanDub))
        assertEquals(setOf(SourceLanguage.EnglishSub), AniWorldLanguageIndex.languages("/anime/stream/one-piece"))
        assertEquals(setOf(SourceLanguage.GermanDub), AniWorldLanguageIndex.languages("/anime/stream/bleach"))
        assertEquals(emptySet(), AniWorldLanguageIndex.languages("/anime/stream/evil"))
        assertNull(AniWorldLanguageIndex.animeKey("/anime/stream/"))
        assertEquals(2, AniWorldLanguageIndex.size())
    }
}
