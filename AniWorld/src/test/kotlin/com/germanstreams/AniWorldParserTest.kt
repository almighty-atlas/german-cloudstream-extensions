package com.germanstreams

import com.germanstreams.common.SourceLanguage
import com.germanstreams.common.parse.AniWorldParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression cover for the aniworld.to parser.
 *
 * These run against **synthetic** fixtures, not captures: the site answers 403 from CI and
 * from the dev sandbox on every path, so nothing here can be verified against it. That makes
 * these tests a guard against refactoring the parser by accident — not evidence that the live
 * markup still matches. The serienstream and filmo suites, which do use real captures, are
 * the ones that catch a redesign.
 */
class AniWorldParserTest {

    @Test
    fun `season navigation collects staffel and filme links`() {
        val hrefs = AniWorldParser.seasonHrefs(Fixtures.document("aniworld-season-synthetic.html"))
        assertEquals(
            listOf(
                "/anime/stream/test-anime/staffel-1",
                "/anime/stream/test-anime/staffel-2",
                "/anime/stream/test-anime/filme",
            ),
            hrefs,
        )
    }

    @Test
    fun `season numbers come from the url and movies sort first`() {
        assertEquals(1, AniWorldParser.seasonNumber("/anime/stream/x/staffel-1"))
        assertEquals(7, AniWorldParser.seasonNumber("/anime/stream/x/staffel-7"))
        assertEquals(0, AniWorldParser.seasonNumber("/anime/stream/x/filme"))
    }

    @Test
    fun `episode rows prefer the german title`() {
        val episodes = AniWorldParser.episodes(Fixtures.document("aniworld-season-synthetic.html"))
        assertEquals(2, episodes.size)
        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals("Der Anfang", episodes.first().title)
    }

    /**
     * The flags on a season page are a static legend — all three are always rendered — so
     * reading them would mark every episode as dubbed. The parser must leave them alone.
     */
    @Test
    fun `season page flags are ignored because they are a legend`() {
        val episodes = AniWorldParser.episodes(Fixtures.document("aniworld-season-synthetic.html"))
        assertTrue(
            episodes.all { it.languages.isEmpty() },
            "season page flags leaked in as real availability: ${episodes.map { it.languages }}",
        )
    }

    @Test
    fun `episode page hosters carry the real language keys`() {
        val doc = Fixtures.document("aniworld-episode-synthetic.html")

        assertEquals(
            setOf(SourceLanguage.GermanDub, SourceLanguage.GermanSub, SourceLanguage.EnglishSub),
            AniWorldParser.hosterLanguages(doc),
        )

        val hosters = AniWorldParser.hosters(doc)
        assertEquals(3, hosters.size)
        assertEquals(SourceLanguage.GermanDub, hosters.first().language)
        assertTrue(hosters.all { it.redirect.startsWith("/redirect/") })
    }

    @Test
    fun `series metadata prefers the full description over the clamped one`() {
        val meta = AniWorldParser.meta(Fixtures.document("aniworld-season-synthetic.html"))
        assertEquals("Test Anime", meta.title)
        assertEquals("Die vollständige Beschreibung.", meta.plot)
        assertEquals(listOf("Action", "Comedy"), meta.tags)
        // The cover is lazy-loaded; the base64 placeholder in src must not win.
        assertEquals("/cover/test.jpg", meta.poster)
    }
}
