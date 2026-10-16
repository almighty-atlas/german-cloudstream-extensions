package com.germanstreams

import com.germanstreams.common.AniWorldCardPresentation
import com.germanstreams.common.AniWorldLanguageIndex
import com.germanstreams.common.SourceLanguage
import com.germanstreams.common.parse.AniWorldParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AniWorldCardPresentationTest {
    @Test
    fun `known languages precede long titles on TV cards`() {
        val fields = AniWorldCardPresentation.fields(
            "JoJo's Bizarre Adventure (2012)",
            setOf(SourceLanguage.GermanDub, SourceLanguage.GermanSub, SourceLanguage.EnglishSub),
        )
        assertEquals(
            "GerDub · GerSub · EngSub | JoJo's Bizarre Adventure (2012)",
            fields.displayName,
        )
        assertTrue(fields.hasDubBadge)
        assertTrue(fields.hasSubBadge)
    }

    @Test
    fun `native badge bits are correct for all language combinations`() {
        for (mask in 0..7) {
            val langs = buildSet {
                if (mask and 1 != 0) add(SourceLanguage.GermanDub)
                if (mask and 2 != 0) add(SourceLanguage.GermanSub)
                if (mask and 4 != 0) add(SourceLanguage.EnglishSub)
            }
            val actual = AniWorldCardPresentation.fields("Test Anime", langs)
            assertEquals(SourceLanguage.GermanDub in langs, actual.hasDubBadge, "dub mask $mask")
            assertEquals(
                SourceLanguage.GermanSub in langs || SourceLanguage.EnglishSub in langs,
                actual.hasSubBadge, "sub mask $mask",
            )
            if (mask == 0) assertEquals("Test Anime", actual.displayName)
        }
    }

    @Test
    fun `engsub only is not falsely labelled gersub`() {
        val actual = AniWorldCardPresentation.fields("Film", setOf(SourceLanguage.EnglishSub))
        assertEquals("EngSub | Film", actual.displayName)
        assertFalse(actual.hasDubBadge)
        assertTrue(actual.hasSubBadge)
    }

    @Test
    fun `real feed capture becomes four independently classified anime cards`() {
        val entries = AniWorldParser.newEpisodeEntries(Fixtures.document("aniworld-new-episodes-live.html"))
        val feedCards = AniWorldCardPresentation.feedCards(entries)
        assertEquals(4, feedCards.size)
        val observed = feedCards.associateBy { it.animeHref }
        assertEquals(
            setOf(SourceLanguage.GermanDub, SourceLanguage.GermanSub, SourceLanguage.EnglishSub),
            observed.getValue("/anime/stream/jojos-bizarre-adventure").languages,
        )
        assertEquals(
            setOf(SourceLanguage.GermanSub, SourceLanguage.EnglishSub),
            observed.getValue("/anime/stream/link-click").languages,
        )
        assertEquals(
            setOf(SourceLanguage.EnglishSub),
            observed.getValue("/anime/stream/the-prince-of-tennis").languages,
        )
        assertEquals(
            setOf(SourceLanguage.GermanDub),
            observed.getValue("/anime/stream/bleach").languages,
        )
        AniWorldLanguageIndex.clear()
        feedCards.forEach { AniWorldLanguageIndex.observe(it.animeHref, it.languages) }
        assertTrue(
            AniWorldCardPresentation.fields(
                observed.getValue("/anime/stream/link-click").title,
                AniWorldLanguageIndex.languages("/anime/stream/link-click"),
            ).displayName.startsWith("GerSub · EngSub | "),
        )
    }

    @Test
    fun `preexisting positive evidence remains when feed adds another language`() {
        AniWorldLanguageIndex.clear()
        val href = "/anime/stream/link-click"
        AniWorldLanguageIndex.observe(href, setOf(SourceLanguage.GermanDub))
        val cards = AniWorldCardPresentation.feedCards(
            AniWorldParser.newEpisodeEntries(Fixtures.document("aniworld-new-episodes-live.html")),
        )
        cards.forEach { AniWorldLanguageIndex.observe(it.animeHref, it.languages) }
        val shown = AniWorldCardPresentation.fields(
            "Link Click", AniWorldLanguageIndex.languages(href),
        )
        assertEquals("GerDub · GerSub · EngSub | Link Click", shown.displayName)
    }
}
