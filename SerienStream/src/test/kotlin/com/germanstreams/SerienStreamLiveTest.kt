package com.germanstreams

import com.germanstreams.common.LiveFetch
import com.germanstreams.common.parse.SerienStreamParser
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Answers the question the fixture tests cannot: does the live site still look like the
 * capture the parsers were written against?
 *
 * Runs only with `SMOKE=1`, which the nightly workflow sets. A failure here means the site was
 * redesigned and the provider is about to stop working — the point is to learn that from CI
 * rather than from a black screen on the TV.
 */
class SerienStreamLiveTest {

    private val mainUrl = "https://serienstream.to"

    @Test
    fun `catalog still yields cards`() {
        if (!LiveFetch.enabled) return
        val doc = Jsoup.parse(LiveFetch.html("$mainUrl/beliebte-serien"), "$mainUrl/")
        val sections = SerienStreamParser.sections(doc)
        val cards = SerienStreamParser.cards(doc)
        assertTrue(
            sections.isNotEmpty() || cards.size >= 10,
            "/beliebte-serien parsed to nothing — the catalog selectors are stale",
        )
    }

    @Test
    fun `genre listing still yields cards`() {
        if (!LiveFetch.enabled) return
        val doc = Jsoup.parse(LiveFetch.html("$mainUrl/genre/action"), "$mainUrl/")
        assertTrue(
            SerienStreamParser.cards(doc).size >= 10,
            "/genre/action parsed to nothing — the listing selectors are stale",
        )
    }

    @Test
    fun `search still yields results`() {
        if (!LiveFetch.enabled) return
        val doc = Jsoup.parse(LiveFetch.html("$mainUrl/suche?term=silo&tab=shows"), "$mainUrl/")
        assertTrue(
            SerienStreamParser.searchResults(doc).isNotEmpty(),
            "search parsed to nothing — the search selectors are stale",
        )
    }

    @Test
    fun `detail page still yields metadata, seasons and episodes`() {
        if (!LiveFetch.enabled) return
        val doc = Jsoup.parse(LiveFetch.html("$mainUrl/serie/silo"), "$mainUrl/")

        val meta = SerienStreamParser.meta(doc)
        assertTrue(meta != null && meta.title.isNotBlank(), "detail metadata is stale")
        assertTrue(SerienStreamParser.seasonHrefs(doc).isNotEmpty(), "season nav is stale")

        val episodes = SerienStreamParser.episodes(doc)
        assertTrue(episodes.isNotEmpty(), "episode rows are stale")
        assertTrue(
            episodes.any { it.languages.isNotEmpty() },
            "no language flags parsed — the per-episode dub markers are stale",
        )
    }

    @Test
    fun `episode page still exposes play buttons`() {
        if (!LiveFetch.enabled) return
        val doc = Jsoup.parse(
            LiveFetch.html("$mainUrl/serie/silo/staffel-1/episode-1"), "$mainUrl/"
        )
        val buttons = SerienStreamParser.playButtons(doc)
        assertTrue(buttons.isNotEmpty(), "no play buttons — link resolution is dead")
        assertTrue(
            buttons.any { it.url.contains("t=") },
            "play URLs no longer carry a token: ${buttons.map { it.url.take(20) }}",
        )
    }
}
