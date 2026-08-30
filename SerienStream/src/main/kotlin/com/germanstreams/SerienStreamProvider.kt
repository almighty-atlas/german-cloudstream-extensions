package com.germanstreams

import com.germanstreams.common.Net
import com.germanstreams.common.SourceCollector
import com.germanstreams.common.SourceLanguage
import com.germanstreams.common.parse.ParsedCard
import com.germanstreams.common.parse.ParsedEpisode
import com.germanstreams.common.parse.SerienStreamParser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document

class SerienStreamProvider : MainAPI() {
    // s.to redirects here; the site moved to this domain in mid-2026. If it moves again this
    // is the only line that needs to change.
    override var mainUrl = "https://serienstream.to"
    override var name = "SerienStream ★"
    override val hasMainPage = true
    override var lang = "de"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    /**
     * Lets the user point the provider at a new domain from the app's provider settings.
     *
     * These sites move — s.to became serienstream.to — and until now that meant the plugin
     * was dead until a new build shipped. CloudStream's own override covers exactly this, so
     * it is enabled explicitly rather than left to the default.
     */
    override var canBeOverridden = true

    override val mainPage = mainPageOf(
        "$mainUrl/beliebte-serien" to "Beliebte Serien",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/comedy" to "Comedy",
        "$mainUrl/genre/drama" to "Drama",
        "$mainUrl/genre/science-fiction" to "Science Fiction",
        "$mainUrl/genre/mystery" to "Mystery",
        "$mainUrl/genre/anime" to "Anime",
        "$mainUrl/genre/zeichentrick" to "Zeichentrick",
    )

    private suspend fun document(url: String, referer: String = "$mainUrl/"): Document =
        app.get(url, referer = referer, headers = Net.browserHeaders, timeout = Net.TIMEOUT).document

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Only the genre listings paginate; /beliebte-serien is a single curated page.
        val isGenre = "/genre/" in request.data
        if (page > 1 && !isGenre) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = if (isGenre && page > 1) "${request.data}?page=$page" else request.data
        val doc = document(url)

        if (!isGenre) {
            val sections = SerienStreamParser.sections(doc)
                .map { HomePageList(it.title, it.cards.map { card -> card.toSearchResponse() }) }
            if (sections.isNotEmpty()) return newHomePageResponse(sections, hasNext = false)
        }

        val items = SerienStreamParser.cards(doc).map { it.toSearchResponse() }
        // A genre page that came back empty is the end of the list, not an error.
        return newHomePageResponse(request.name, items, hasNext = isGenre && items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/suche",
            params = mapOf("term" to query, "tab" to "shows"),
            referer = "$mainUrl/suche",
            headers = Net.browserHeaders,
            timeout = Net.TIMEOUT,
        ).document
        return SerienStreamParser.searchResults(doc).map { it.toSearchResponse() }
    }

    private fun ParsedCard.toSearchResponse(): SearchResponse =
        newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster)
        }

    override suspend fun load(url: String): LoadResponse? {
        val doc = document(url)
        val meta = SerienStreamParser.meta(doc) ?: return null

        val seasonHrefs = SerienStreamParser.seasonHrefs(doc)
        val parsed: List<Pair<Int, ParsedEpisode>> = if (seasonHrefs.isEmpty()) {
            // Single-season shows render the episode table straight onto the detail page.
            SerienStreamParser.episodes(doc).map { 1 to it }
        } else {
            doc.select("#season-nav ul > li a[href]").amap { a ->
                val href = a.attr("href")
                val seasonUrl = fixUrl(href)
                val number = SerienStreamParser.seasonNumber(href, a.text())
                val sdoc = if (seasonUrl == url) doc else document(seasonUrl, referer = url)
                SerienStreamParser.episodes(sdoc).map { number to it }
            }.flatten()
        }

        val episodes = parsed
            .sortedBy { it.second.number ?: 0 }
            .sortedBy { it.first }
            .map { (season, ep) -> ep.toEpisode(season) }

        // Season labels get a flag when any episode in them offers a German dub, so the
        // spinner says at a glance which seasons are watchable dubbed.
        val seasonNames = parsed.groupBy { it.first }.toSortedMap().map { (season, eps) ->
            val label = if (season == 0) "Filme" else "Staffel $season"
            SeasonData(season, if (eps.any { it.second.hasGermanDub }) "$label 🇩🇪" else label)
        }

        return newTvSeriesLoadResponse(meta.title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(meta.poster)
            this.backgroundPosterUrl = fixUrlNull(SerienStreamParser.backdrop(doc))
            this.year = meta.year
            this.plot = meta.plot
            this.tags = meta.tags
            this.contentRating = SerienStreamParser.ageRating(doc)
            addActors(meta.actors.map { it.name })
            addTrailer(meta.trailer)
            addSeasonNames(seasonNames)
        }
    }

    /**
     * The episode rows carry the real language availability and the hoster icons, so both are
     * surfaced without a single extra request — a 🇩🇪 flag for a German dub, the hoster name
     * so a dead-link episode is recognisable before opening it.
     */
    private fun ParsedEpisode.toEpisode(season: Int): Episode {
        val flags = listOfNotNull(
            "🇩🇪".takeIf { hasGermanDub },
            "🇬🇧".takeIf { SourceLanguage.EnglishSub in languages && !hasGermanDub },
        ).joinToString("")
        val label = listOfNotNull(title?.ifBlank { null }, flags.ifBlank { null })
            .joinToString(" ")
            .ifBlank { null }

        return newEpisode(fixUrl(href)) {
            this.name = label
            this.season = season
            this.episode = number
            this.description = hosters.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = document(fixUrl(data))
        val collector = SourceCollector(mainUrl)

        // The current markup exposes play buttons; the pre-2026 hoster list is kept as a
        // fallback because sibling sites in this family still serve it.
        val buttons = SerienStreamParser.playButtons(doc)
            .ifEmpty { SerienStreamParser.legacyHosters(doc) }

        buttons.amap { button ->
            collector.addRedirect(
                redirectUrl = fixUrl(button.url),
                language = button.language,
                captionOverride = button.hoster,
                subtitleCallback = subtitleCallback,
            )
        }

        return collector.emitTo(callback)
    }
}
