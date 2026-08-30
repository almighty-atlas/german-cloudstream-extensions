package com.germanstreams.common.parse

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Selectors for filmo.to, verified against the live site. Movies only — the site has no
 * series section.
 *
 * Three different card layouts exist and they disagree about where the title lives: spotlight
 * cards put it in an `<h4>`, grid cards in a `__title` div, swiper cards only in the image's
 * alt text. Bnyro's `[class*=title]` returns empty here, so it is deliberately not used.
 */
object FilmoParser {

    private const val CARD_SELECTOR =
        ".popular-spotlight-card__link, a.video-card, a.movie-poster-grid-card"

    // --- listings ------------------------------------------------------------------------

    fun sections(doc: Document): List<ParsedSection> =
        doc.select("section.popular-spotlight, div.video-row").mapNotNull { section ->
            val heading = section.selectFirst("h3") ?: return@mapNotNull null
            val title = heading.ownText().trim().ifBlank { heading.text().trim() }
                .ifBlank { return@mapNotNull null }
            val cards = cards(section)
            if (cards.isEmpty()) null else ParsedSection(title, cards)
        }

    fun cards(root: Element): List<ParsedCard> =
        root.select(CARD_SELECTOR).mapNotNull { it.toCard() }.distinctBy { it.href }

    fun searchResults(doc: Document): List<ParsedCard> {
        val top = doc.select("section.search-top-results article > a").mapNotNull { it.toCard() }
        return (top + cards(doc)).distinctBy { it.href }
    }

    private fun Element.toCard(): ParsedCard? {
        val href = (if (tagName() == "a") attr("href") else selectFirst("a[href]")?.attr("href"))
            ?.trim()?.ifBlank { null } ?: return null
        if (!href.contains("/movies/")) return null

        val img = selectFirst("img")
        val title = selectFirst("[class*=__title]")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("h4")?.text()?.trim()?.ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        return ParsedCard(title, href, Images.from(selectFirst(".ft-packshot img") ?: img))
    }

    /** Highest `?page=N` in the pager, so the provider knows when to stop asking for more. */
    fun lastPage(doc: Document): Int? =
        doc.select("a[href*=page=]")
            .mapNotNull { Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()

    // --- detail --------------------------------------------------------------------------

    fun meta(doc: Document): ParsedMeta? {
        val title = doc.selectFirst(".primary-container h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: return null

        val details = details(doc)

        return ParsedMeta(
            title = title,
            poster = Images.from(doc.selectFirst("img.ft-packshot-meta")),
            plot = doc.selectFirst("p.movie-detail-synopsis")?.text()?.trim()?.ifBlank { null },
            year = details["Erscheinungsdatum"]?.take(4)?.toIntOrNull(),
            tags = details["Genres"].splitList(),
            actors = actors(doc, details),
            trailer = trailer(doc),
            rating = rating(details["Bewertung"]),
            durationMinutes = durationMinutes(details["Laufzeit"]),
            recommendations = recommendations(doc),
        )
    }

    /** The `Mehr Infos` block: `<dl><dt>Laufzeit</dt><dd>181 Min.</dd></dl>` per entry. */
    fun details(doc: Document): Map<String, String> =
        doc.select("div.details-group dl").mapNotNull { dl ->
            val key = dl.selectFirst("dt")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val value = dl.selectFirst("dd")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            key to value
        }.toMap()

    /**
     * Cast with headshots. The names link to `/people/<slug>` pages that carry a portrait,
     * which CloudStream renders in the cast row; a bare list of names shows placeholders.
     */
    private fun actors(doc: Document, details: Map<String, String>): List<ParsedActor> {
        val linked = doc.select("div.details-group dl:has(dt:contains(Darsteller)) dd a")
            .mapNotNull { a ->
                val name = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                ParsedActor(name, Images.from(a.selectFirst("img")))
            }
        return linked.ifEmpty { details["Darsteller"].splitList().map { ParsedActor(it) } }
    }

    /** The trailer modal embeds YouTube; CloudStream resolves a watch URL by itself. */
    private fun trailer(doc: Document): String? =
        doc.select("a[href*=youtube.com/watch], a[href*=youtu.be]")
            .firstOrNull()?.attr("href")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("iframe[src*=youtube.com/embed]")?.attr("src")
                ?.substringAfter("/embed/")?.substringBefore('?')?.ifBlank { null }
                ?.let { "https://www.youtube.com/watch?v=$it" }

    /** "Verwandte Filme" — the one swiper on a detail page. */
    private fun recommendations(doc: Document): List<ParsedCard> =
        doc.select("div.swiper-wrapper a.video-card").mapNotNull { it.toCard() }
            .distinctBy { it.href }

    /** "8.0 / 10 (12,017 Stimmen)" -> 8.0 */
    fun rating(raw: String?): Double? {
        val text = raw ?: return null
        val value = Regex("(\\d+[.,]?\\d*)\\s*/\\s*10").find(text)?.groupValues?.get(1)
            ?: Regex("^\\s*(\\d+[.,]?\\d*)").find(text)?.groupValues?.get(1)
            ?: return null
        return value.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
    }

    /** Handles both "181 Min." and the "2 h 31 min" form used in the card overlays. */
    fun durationMinutes(raw: String?): Int? {
        val text = raw?.lowercase() ?: return null
        val hours = Regex("(\\d+)\\s*(?:h|std)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = Regex("(\\d+)\\s*(?:min)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (hours != null || minutes != null) {
            return (hours ?: 0) * 60 + (minutes ?: 0)
        }
        return Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun String?.splitList(): List<String> =
        this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    // --- link resolution -----------------------------------------------------------------

    data class ProviderChip(val payload: String, val caption: String)

    fun providerChips(doc: Document): List<ProviderChip> =
        doc.select(".provider-chip[data-p]").mapNotNull { chip ->
            val payload = chip.attr("data-p").trim().ifBlank { null } ?: return@mapNotNull null
            ProviderChip(payload, chip.text().replace(Regex("\\s+"), " ").trim())
        }.distinctBy { it.payload }

    /**
     * Hosts that answer the one-shot slug with an interstitial page rather than a redirect.
     * Picking the first outbound link would just as happily grab a share or social link, so
     * the candidates are filtered against the hosts these providers actually use.
     */
    private val HOSTER_HINTS = listOf(
        "voe", "filemoon", "vidmoly", "doodstream", "dood", "streamtape", "vidoza",
        "upstream", "mixdrop", "supervideo", "luluvdo", "vidhide", "streamwish", "byse",
        "bigwarp", "lulustream", "filelions", "moon", "vido", "streamvid",
    )

    fun interstitialTarget(doc: Document, siteHost: String): String? {
        val links = doc.select("a[href]").map { it.attr("href").trim() }
            .filter { it.startsWith("http") && !it.contains(siteHost, ignoreCase = true) }
        return links.firstOrNull { link -> HOSTER_HINTS.any { it in link.lowercase() } }
            ?: links.firstOrNull { "/embed" in it.lowercase() || "/e/" in it.lowercase() }
    }
}
