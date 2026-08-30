package com.germanstreams.common.parse

import com.germanstreams.common.SourceLanguage
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Selectors for aniworld.to.
 *
 * aniworld.to still serves the pre-2026 layout that serienstream.to has since dropped, so the
 * two are parsed separately on purpose. The site is unreachable from the build sandbox
 * (DDoS-Guard answers 403 to every path, the ajax endpoints included), which is why these
 * selectors are conservative copies of what the provider already used in production rather
 * than anything newly derived.
 */
object AniWorldParser {

    fun cards(doc: Document): List<ParsedCard> =
        doc.select("div.coverListItem, div.col-md-15, div.seriesListContainer > div")
            .mapNotNull { it.toCard() }
            .distinctBy { it.href }

    private fun Element.toCard(): ParsedCard? {
        val a = selectFirst("a[href*=/anime/stream/]") ?: return null
        val href = a.attr("href").trim().ifBlank { null } ?: return null
        val title = selectFirst("h3")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").substringBefore(" stream").trim().ifBlank { null }
            ?: a.text().trim().ifBlank { null }
            ?: return null
        return ParsedCard(title, href, Images.from(selectFirst("img")))
    }

    /**
     * Rows on /neue-episoden, which — unlike the plain catalog grids — carry a real German
     * dub flag per entry. That makes it the only listing on this site where the language is
     * free; everywhere else it costs one request per episode page.
     */
    fun newEpisodeCards(doc: Document): List<Pair<ParsedCard, Boolean>> =
        doc.select("div.coverListItem, div.col-md-15, table tr:has(a[href*=/anime/stream/])")
            .mapNotNull { row ->
                val card = row.toCard() ?: return@mapNotNull null
                val dub = row.select("img[src*=german], svg.flag-german, .flag-german").isNotEmpty()
                card to dub
            }
            .distinctBy { it.first.href }

    fun seasonHrefs(doc: Document): List<String> =
        doc.select("a[href*=/anime/stream/]")
            .map { it.attr("href").trim() }
            .filter { (it.contains("/staffel-") || it.endsWith("/filme")) && !it.contains("/episode-") }
            .distinct()

    fun seasonNumber(href: String): Int =
        Regex("/staffel-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (href.endsWith("/filme")) 0 else 1

    /**
     * Episode rows of one season page.
     *
     * The language flags rendered here are a **static legend** — all three are always shown
     * regardless of what the episode actually offers — so they are deliberately not read.
     * Real availability only exists on the episode page itself, via [hosterLanguages].
     */
    fun episodes(doc: Document): List<ParsedEpisode> =
        doc.select("table.seasonEpisodesList tbody tr").mapNotNull { row ->
            val a = row.selectFirst("td.seasonEpisodeTitle a") ?: return@mapNotNull null
            val href = a.attr("href").trim().ifBlank { null } ?: return@mapNotNull null
            ParsedEpisode(
                href = href,
                number = Regex("/episode-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull(),
                title = a.selectFirst("strong")?.text()?.trim()?.ifBlank { null }
                    ?: a.selectFirst("span")?.text()?.trim()?.ifBlank { null },
            )
        }

    fun meta(doc: Document): ParsedMeta {
        val title = doc.selectFirst("div.series-title h1 span")?.text()?.trim()
            ?: doc.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
        return ParsedMeta(
            title = title,
            poster = Images.from(doc.selectFirst("div.seriesCoverBox img")),
            plot = doc.selectFirst(".seri_des")?.attr("data-full-description")?.trim()?.ifBlank { null }
                ?: doc.selectFirst(".seri_des")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("[itemprop=description]")?.text()?.trim()?.ifBlank { null },
            tags = doc.select("div.genres a[itemprop=genre], .genres li a")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            year = doc.selectFirst("span[itemprop=startDate] a, .series-title small")
                ?.text()?.trim()?.take(4)?.toIntOrNull(),
            actors = doc.select("li[itemprop=actor] span[itemprop=name], .cast li a")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
                .map { ParsedActor(it) },
        )
    }

    /** The hoster list on an episode page — the only place with real per-episode languages. */
    fun hosterLanguages(doc: Document): Set<SourceLanguage> =
        doc.select("li[data-link-target][data-lang-key]")
            .map { SourceLanguage.fromAniWorldKey(it.attr("data-lang-key")) }
            .filter { it != SourceLanguage.Unknown }
            .toSet()

    data class Hoster(val redirect: String, val language: SourceLanguage)

    fun hosters(doc: Document): List<Hoster> =
        doc.select(
            "div.hosterSiteVideo ul li[data-link-target], li.col-md-3.col-xs-12[data-link-target]"
        ).mapNotNull { li ->
            val target = li.attr("data-link-target").trim().ifBlank {
                li.selectFirst("a.watchEpisode")?.attr("href").orEmpty().trim()
            }.ifBlank { null } ?: return@mapNotNull null
            Hoster(target, SourceLanguage.fromAniWorldKey(li.attr("data-lang-key")))
        }.distinctBy { it.redirect }
}
