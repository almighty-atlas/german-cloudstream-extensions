package com.germanstreams

import com.germanstreams.common.SourceLanguage
import com.germanstreams.common.parse.SerienStreamParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the serienstream.to selectors against a site redesign.
 *
 * A green compile says nothing about whether the selectors still match anything — that is the
 * failure mode this repo actually hits, and previously it only surfaced on the user's TV.
 */
class SerienStreamParserTest {

    // --- listings ------------------------------------------------------------------------

    @Test
    fun `popular page yields titled sections with cards`() {
        val sections = SerienStreamParser.sections(Fixtures.document("serienstream-popular.html"))
        assertTrue(sections.isNotEmpty(), "no sections parsed from /beliebte-serien")
        assertTrue(
            sections.any { it.cards.size >= 10 },
            "expected at least one substantial row, got ${sections.map { it.title to it.cards.size }}",
        )
        sections.forEach { section ->
            assertTrue(section.title.isNotBlank(), "section without a title")
            section.cards.forEach { card ->
                assertTrue(card.title.isNotBlank(), "card without a title in ${section.title}")
                assertTrue(card.href.contains("/serie/"), "card href is not a series: ${card.href}")
            }
        }
    }

    @Test
    fun `genre listing pages parse as a flat grid`() {
        val cards = SerienStreamParser.cards(
            Fixtures.document("serienstream-genre-action-p2.html")
        )
        assertTrue(cards.size >= 20, "expected a full genre page, got ${cards.size}")
        assertTrue(cards.all { it.title.isNotBlank() })
        assertTrue(cards.all { it.href.contains("/serie/") })
    }

    @Test
    fun `cards carry a real poster and never a lazy-loading placeholder`() {
        val cards = SerienStreamParser.cards(
            Fixtures.document("serienstream-genre-action-p2.html")
        )
        val withPoster = cards.filter { it.poster != null }
        assertTrue(withPoster.size >= cards.size / 2, "most cards should have a poster")
        assertTrue(
            withPoster.none { it.poster!!.startsWith("data:") },
            "a base64 placeholder leaked through as a poster URL",
        )
    }

    @Test
    fun `search finds the queried show`() {
        val results = SerienStreamParser.searchResults(
            Fixtures.document("serienstream-search-silo.html")
        )
        assertTrue(results.isNotEmpty(), "search page parsed to nothing")
        assertTrue(
            results.any { it.href.endsWith("/serie/silo") },
            "expected /serie/silo among ${results.take(5).map { it.href }}",
        )
    }

    // --- detail --------------------------------------------------------------------------

    @Test
    fun `detail page metadata`() {
        val doc = Fixtures.document("serienstream-detail-silo.html")
        val meta = assertNotNull(SerienStreamParser.meta(doc))

        assertEquals("Silo", meta.title)
        assertEquals(2023, meta.year)
        assertTrue(meta.plot!!.contains("Silo"), "plot did not parse: ${meta.plot}")
        assertTrue(meta.tags.contains("Drama"), "genres did not parse: ${meta.tags}")
        assertTrue(
            meta.actors.any { it.name == "Rebecca Ferguson" },
            "cast did not parse: ${meta.actors.map { it.name }}",
        )
        assertTrue(meta.trailer!!.contains("youtu"), "trailer did not parse: ${meta.trailer}")
    }

    @Test
    fun `poster is the cover and not the backdrop`() {
        val doc = Fixtures.document("serienstream-detail-silo.html")
        val poster = assertNotNull(SerienStreamParser.meta(doc)!!.poster)
        // Both images sit in the same header container and only the alt text tells them apart.
        assertTrue(poster.contains("/channel/"), "picked the backdrop as poster: $poster")
        assertTrue(!poster.startsWith("data:"), "picked the lazy-loading placeholder: $poster")

        val backdrop = assertNotNull(SerienStreamParser.backdrop(doc))
        assertTrue(backdrop.contains("/backdrop/"), "backdrop did not parse: $backdrop")
    }

    @Test
    fun `age rating comes off the metadata line`() {
        assertEquals(
            "FSK 12",
            SerienStreamParser.ageRating(Fixtures.document("serienstream-detail-silo.html")),
        )
    }

    @Test
    fun `season navigation lists every season exactly once`() {
        val hrefs = SerienStreamParser.seasonHrefs(
            Fixtures.document("serienstream-detail-silo.html")
        )
        assertEquals(listOf(1, 2, 3), hrefs.map { SerienStreamParser.seasonNumber(it, "") })
    }

    @Test
    fun `season number falls back to the url when the link text is not a number`() {
        assertEquals(2, SerienStreamParser.seasonNumber("/serie/x/staffel-2", "Staffel 2"))
        assertEquals(4, SerienStreamParser.seasonNumber("/serie/x/staffel-4", ""))
        assertEquals(0, SerienStreamParser.seasonNumber("/serie/x/filme", "Filme"))
    }

    // --- episodes ------------------------------------------------------------------------

    @Test
    fun `episode rows parse number, title and target`() {
        val episodes = SerienStreamParser.episodes(
            Fixtures.document("serienstream-detail-silo.html")
        )
        assertEquals(10, episodes.size)
        assertEquals((1..10).toList(), episodes.map { it.number })
        assertEquals("/serie/silo/staffel-1/episode-1", episodes.first().href)
        // The row carries a German and an English title; both belong in the label.
        assertTrue(
            episodes.first().title!!.contains("Freiheitstag"),
            "episode title did not parse: ${episodes.first().title}",
        )
    }

    @Test
    fun `episode rows carry the advertised hoster`() {
        val episodes = SerienStreamParser.episodes(
            Fixtures.document("serienstream-detail-silo.html")
        )
        assertTrue(
            episodes.all { it.hosters.contains("VOE") },
            "hoster icons did not parse: ${episodes.map { it.hosters }}",
        )
    }

    /**
     * The important one: these flags are real availability, not the static all-three legend
     * that the aniworld family renders. Silo offers a German dub, 18if does not — if that
     * distinction ever collapses, the flags have become decoration and must not be shown.
     */
    @Test
    fun `episode language flags distinguish a dubbed show from a subbed one`() {
        val dubbed = SerienStreamParser.episodes(
            Fixtures.document("serienstream-detail-silo.html")
        )
        assertTrue(dubbed.all { it.hasGermanDub }, "Silo should be flagged as dubbed")
        assertTrue(dubbed.all { SourceLanguage.EnglishSub in it.languages })

        val subbedOnly = SerienStreamParser.episodes(
            Fixtures.document("serienstream-detail-18if.html")
        )
        assertTrue(subbedOnly.isNotEmpty(), "18if parsed to no episodes")
        assertTrue(subbedOnly.none { it.hasGermanDub }, "18if has no German dub but was flagged")
        assertTrue(
            subbedOnly.all { SourceLanguage.GermanSub in it.languages },
            "18if offers German subtitles: ${subbedOnly.map { it.languages }}",
        )
    }

    // --- play buttons --------------------------------------------------------------------

    @Test
    fun `episode page play buttons carry language and hoster`() {
        val buttons = SerienStreamParser.playButtons(
            Fixtures.document("serienstream-episode-silo-s1e1.html")
        )
        assertTrue(buttons.isNotEmpty(), "no play buttons parsed")
        assertTrue(buttons.all { it.url.startsWith("/r?t=") }, "play URLs changed shape")
        assertTrue(buttons.any { it.language == SourceLanguage.GermanDub })
        assertTrue(buttons.any { it.language == SourceLanguage.EnglishSub })
        assertTrue(buttons.any { it.hoster == "VOE" })
        // "Provider" is a placeholder, not a host, and must not be offered as a caption.
        assertTrue(
            buttons.none { it.hoster.equals("Provider", ignoreCase = true) },
            "the placeholder provider name leaked through",
        )
    }
}
