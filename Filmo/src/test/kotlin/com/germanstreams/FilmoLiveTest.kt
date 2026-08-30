package com.germanstreams

import com.germanstreams.common.LiveFetch
import com.germanstreams.common.parse.FilmoParser
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live counterpart to [FilmoParserTest]; see [SerienStreamLiveTest] for the rationale.
 * Runs only with `SMOKE=1`.
 */
class FilmoLiveTest {

    private val mainUrl = "https://filmo.to"

    @Test
    fun `catalog still yields cards`() {
        if (!LiveFetch.enabled) return
        val doc = Jsoup.parse(LiveFetch.html("$mainUrl/popular"), "$mainUrl/")
        assertTrue(
            FilmoParser.sections(doc).isNotEmpty() || FilmoParser.cards(doc).size >= 10,
            "/popular parsed to nothing — the catalog selectors are stale",
        )
    }

    @Test
    fun `paginated grid still yields cards and a pager`() {
        if (!LiveFetch.enabled) return
        val doc = Jsoup.parse(LiveFetch.html("$mainUrl/movies?page=2"), "$mainUrl/")
        assertTrue(FilmoParser.cards(doc).size >= 20, "/movies grid is stale")
        assertTrue(FilmoParser.lastPage(doc) != null, "the pager is stale — paging will stop early")
    }

    @Test
    fun `search still yields results`() {
        if (!LiveFetch.enabled) return
        val doc = Jsoup.parse(LiveFetch.html("$mainUrl/search?q=oppenheimer"), "$mainUrl/")
        assertTrue(
            FilmoParser.searchResults(doc).isNotEmpty(),
            "search parsed to nothing — the search selectors are stale",
        )
    }

    @Test
    fun `detail page still yields metadata and provider chips`() {
        if (!LiveFetch.enabled) return
        val doc = Jsoup.parse(LiveFetch.html("$mainUrl/movies/oppenheimer"), "$mainUrl/")

        val meta = assertNotNull(FilmoParser.meta(doc), "detail metadata is stale")
        assertTrue(meta.title.isNotBlank(), "the title is stale")
        assertTrue(meta.year != null, "release date is stale")
        assertTrue(meta.rating != null, "the rating line is stale")
        assertTrue(meta.recommendations.isNotEmpty(), "'Verwandte Filme' is stale")

        assertTrue(
            FilmoParser.providerChips(doc).isNotEmpty(),
            "no provider chips — link resolution is dead",
        )
    }
}
