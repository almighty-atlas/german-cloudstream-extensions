package com.germanstreams

import com.germanstreams.common.Net
import com.germanstreams.common.SourceCollector
import com.germanstreams.common.SourceLanguage
import com.germanstreams.common.parse.FilmoParser
import com.germanstreams.common.parse.ParsedCard
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document

class FilmoProvider : MainAPI() {
    override var mainUrl = "https://filmo.to"
    override var name = "Filmo ★"
    override val hasMainPage = true
    override var lang = "de"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    /**
     * Lets the user point the provider at a new domain from the app's provider settings.
     *
     * These sites move — s.to became serienstream.to — and until now that meant the plugin
     * was dead until a new build shipped. CloudStream's own override covers exactly this, so
     * it is enabled explicitly rather than left to the default.
     */
    override var canBeOverridden = true

    override val mainPage = mainPageOf(
        "$mainUrl/popular" to "Beliebt bei Filmo",
        "$mainUrl/movies" to "Alle Filme",
        "$mainUrl/collections/top-kinofilme" to "Top Kinofilme",
        "$mainUrl/collections/top-rated-movies-on-imdb" to "Top-Bewertungen (IMDb)",
        "$mainUrl/collections/action-spannung-grusel" to "Action, Spannung & Grusel",
        "$mainUrl/collections/kids-and-family-movies" to "Kinder & Familie",
    )

    private suspend fun document(url: String, referer: String = "$mainUrl/"): Document =
        app.get(url, referer = referer, headers = Net.browserHeaders, timeout = Net.TIMEOUT).document

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // /popular is a curated single page of titled rows; the listings paginate via ?page=N.
        val paginated = "/popular" !in request.data
        if (page > 1 && !paginated) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = if (paginated && page > 1) "${request.data}?page=$page" else request.data
        val doc = document(url)

        if (!paginated) {
            val sections = FilmoParser.sections(doc)
                .map { HomePageList(it.title, it.cards.map { card -> card.toSearchResponse() }) }
            if (sections.isNotEmpty()) return newHomePageResponse(sections, hasNext = false)
        }

        val items = FilmoParser.cards(doc).map { it.toSearchResponse() }
        val lastPage = FilmoParser.lastPage(doc)
        val hasNext = paginated && items.isNotEmpty() && (lastPage == null || page < lastPage)
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/search",
            params = mapOf("q" to query),
            headers = Net.browserHeaders,
            timeout = Net.TIMEOUT,
        ).document
        return FilmoParser.searchResults(doc).map { it.toSearchResponse() }
    }

    private fun ParsedCard.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = fixUrlNull(poster)
        }

    override suspend fun load(url: String): LoadResponse? {
        val doc = document(url)
        val meta = FilmoParser.meta(doc) ?: return null

        return newMovieLoadResponse(meta.title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(meta.poster)
            this.plot = meta.plot
            this.year = meta.year
            this.duration = meta.durationMinutes
            this.tags = meta.tags
            // The site states an IMDb-style score that CloudStream can render on the card.
            this.score = meta.rating?.let { Score.from10(it) }
            this.recommendations = meta.recommendations.map { it.toSearchResponse() }
            addActors(meta.actors.map { Actor(it.name, fixUrlNull(it.image)) })
            addTrailer(meta.trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // The movie page hands out the XSRF cookie that /n requires, so it has to be fetched
        // first and its cookies carried through every later call.
        val page = app.get(data, headers = Net.browserHeaders, timeout = Net.TIMEOUT)
        val cookies = page.cookies
        // Laravel URL-encodes the cookie; only the base64 padding is affected in practice.
        val xsrf = cookies["XSRF-TOKEN"]?.replace("%3D", "=")

        val chips = FilmoParser.providerChips(page.document)
        if (chips.isEmpty()) return false

        val collector = SourceCollector(mainUrl)
        chips.amap { chip ->
            val target = resolveTarget(chip.payload, xsrf, cookies, data) ?: return@amap
            collector.addTarget(
                target = target,
                // The chips name the release ("VOE WEB-DL 720p") and sometimes the audio
                // track with it; anything unrecognised stays Unknown rather than guessing.
                language = SourceLanguage.fromLabel(chip.caption),
                captionOverride = chip.caption,
                subtitleCallback = subtitleCallback,
            )
        }

        return collector.emitTo(callback)
    }

    /**
     * Turns a provider chip's payload into the hoster URL.
     *
     * POST /n exchanges the payload for a one-shot slug, and GET /n/{slug} resolves it. The
     * slug is consumed by that single request — a second call answers 404 — so this must not
     * retry the same slug. Redirects are therefore left unfollowed and both outcomes handled
     * from one response: VOE-style hosts answer 30x with a Location, Byse-style ones answer
     * 200 with an interstitial page linking out to the host.
     */
    private suspend fun resolveTarget(
        payload: String,
        xsrf: String?,
        cookies: Map<String, String>,
        referer: String,
    ): String? = runCatching {
        val postHeaders = Net.browserHeaders + mapOf(
            "Referer" to referer,
            "Content-Type" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
        ) + (xsrf?.let { mapOf("X-XSRF-TOKEN" to it) } ?: emptyMap())

        val slugRes = app.post(
            "$mainUrl/n",
            json = mapOf("p" to payload),
            cookies = cookies,
            headers = postHeaders,
            timeout = Net.TIMEOUT,
        ).text
        val slug = tryParseJson<SlugResponse>(slugRes)?.x?.ifBlank { null } ?: return@runCatching null

        val res = app.get(
            "$mainUrl/n/$slug",
            cookies = cookies,
            headers = Net.browserHeaders + mapOf("Referer" to referer),
            allowRedirects = false,
            timeout = Net.TIMEOUT,
        )

        res.headers["location"]?.ifBlank { null }?.let { return@runCatching Net.absolutize(it, mainUrl) }
        // No redirect: the response is an interstitial that links out to the host.
        FilmoParser.interstitialTarget(res.document, siteHost = "filmo.to")
    }.getOrNull()

    private data class SlugResponse(val x: String? = null)
}
