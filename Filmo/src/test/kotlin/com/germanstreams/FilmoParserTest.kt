package com.germanstreams

import com.germanstreams.common.parse.FilmoParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the filmo.to selectors against a site redesign. See [Fixtures] for how the pages
 * were captured.
 */
class FilmoParserTest {

    // --- listings ------------------------------------------------------------------------

    @Test
    fun `popular page yields titled sections with cards`() {
        val sections = FilmoParser.sections(Fixtures.document("filmo-popular.html"))
        assertTrue(sections.isNotEmpty(), "no sections parsed from /popular")
        sections.forEach { section ->
            assertTrue(section.title.isNotBlank(), "section without a title")
            section.cards.forEach { card ->
                // Bnyro's [class*=title] returns empty on this site, which is why the parser
                // walks h4 and the image alt text instead.
                assertTrue(card.title.isNotBlank(), "card without a title in ${section.title}")
                assertTrue(card.href.contains("/movies/"), "not a movie link: ${card.href}")
            }
        }
    }

    @Test
    fun `paginated movie grid parses a full page`() {
        val doc = Fixtures.document("filmo-movies-p2.html")
        val cards = FilmoParser.cards(doc)
        assertTrue(cards.size >= 40, "expected a full grid page, got ${cards.size}")
        assertTrue(cards.all { it.title.isNotBlank() })
        assertTrue(cards.none { it.poster?.startsWith("data:") == true })
    }

    @Test
    fun `pager reports the last page so paging can stop`() {
        val last = FilmoParser.lastPage(Fixtures.document("filmo-movies-p2.html"))
        assertNotNull(last)
        assertTrue(last > 100, "expected a three-digit page count, got $last")
    }

    @Test
    fun `search finds the queried movie`() {
        val results = FilmoParser.searchResults(Fixtures.document("filmo-search.html"))
        assertTrue(results.isNotEmpty(), "search page parsed to nothing")
        assertTrue(
            results.any { it.href.endsWith("/movies/oppenheimer") },
            "expected oppenheimer among ${results.take(5).map { it.href }}",
        )
    }

    // --- detail --------------------------------------------------------------------------

    @Test
    fun `detail page metadata`() {
        val meta = assertNotNull(FilmoParser.meta(Fixtures.document("filmo-detail-oppenheimer.html")))
        assertEquals("Oppenheimer", meta.title)
        assertEquals(2023, meta.year)
        assertEquals(181, meta.durationMinutes)
        assertEquals(8.0, meta.rating)
        assertTrue(meta.plot!!.isNotBlank(), "synopsis did not parse")
        assertTrue(meta.tags.containsAll(listOf("Drama", "Historie")), "genres: ${meta.tags}")
        assertTrue(meta.poster!!.contains("http"), "poster did not parse: ${meta.poster}")
    }

    @Test
    fun `cast parses with headshots`() {
        val meta = assertNotNull(FilmoParser.meta(Fixtures.document("filmo-detail-oppenheimer.html")))
        assertTrue(
            meta.actors.any { it.name == "Cillian Murphy" },
            "cast did not parse: ${meta.actors.map { it.name }}",
        )
    }

    @Test
    fun `trailer resolves to a youtube watch url`() {
        val meta = assertNotNull(FilmoParser.meta(Fixtures.document("filmo-detail-oppenheimer.html")))
        val trailer = assertNotNull(meta.trailer, "trailer did not parse")
        assertTrue(trailer.contains("youtu"), "not a youtube link: $trailer")
    }

    @Test
    fun `related movies parse as recommendations`() {
        val meta = assertNotNull(FilmoParser.meta(Fixtures.document("filmo-detail-oppenheimer.html")))
        assertTrue(
            meta.recommendations.size >= 5,
            "expected a full 'Verwandte Filme' row, got ${meta.recommendations.size}",
        )
        assertTrue(meta.recommendations.all { it.title.isNotBlank() })
        assertTrue(meta.recommendations.all { it.href.contains("/movies/") })
    }

    @Test
    fun `provider chips parse with their captions`() {
        val chips = FilmoParser.providerChips(Fixtures.document("filmo-detail-oppenheimer.html"))
        assertTrue(chips.isNotEmpty(), "no provider chips parsed — link resolution is dead")
        assertTrue(chips.all { it.payload.isNotBlank() })
    }

    // --- value parsing -------------------------------------------------------------------

    @Test
    fun `rating reads the score and ignores the vote count`() {
        assertEquals(8.0, FilmoParser.rating("8.0 / 10 (12,017 Stimmen)"))
        assertEquals(7.5, FilmoParser.rating("7,5 / 10"))
        assertNull(FilmoParser.rating(null))
        assertNull(FilmoParser.rating("keine Bewertung"))
    }

    @Test
    fun `duration handles both the minutes and the hours form`() {
        // The detail list says "181 Min."; the card overlays say "2 h 31 min".
        assertEquals(181, FilmoParser.durationMinutes("181 Min."))
        assertEquals(151, FilmoParser.durationMinutes("2 h 31 min"))
        assertEquals(112, FilmoParser.durationMinutes("1 Std. 52 Min."))
        assertNull(FilmoParser.durationMinutes(null))
    }

    @Test
    fun `interstitial target picks a hoster and not a share link`() {
        val html = """
            <html><body>
              <a href="https://twitter.com/intent/tweet?url=x">Teilen</a>
              <a href="https://filmo.to/help">Hilfe</a>
              <a href="https://voe.sx/e/abc123">Weiter zum Video</a>
            </body></html>
        """.trimIndent()
        assertEquals(
            "https://voe.sx/e/abc123",
            FilmoParser.interstitialTarget(org.jsoup.Jsoup.parse(html), "filmo.to"),
        )
    }
}
