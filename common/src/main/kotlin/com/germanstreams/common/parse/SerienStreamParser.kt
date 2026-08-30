package com.germanstreams.common.parse

import com.germanstreams.common.SourceLanguage
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Selectors for serienstream.to, verified against the live site.
 *
 * The site was redesigned in early 2026 and no longer shares markup with aniworld.to, so
 * nothing in here should be reused for that family without checking it first.
 */
object SerienStreamParser {

    private const val CARD_SELECTOR = "a.show-card, .results-group .card, .card"

    // --- listings ------------------------------------------------------------------------

    /** Titled rows on /beliebte-serien. Falls back to one flat grid if the sections change. */
    fun sections(doc: Document): List<ParsedSection> =
        doc.select(".popular-page > div").mapNotNull { section ->
            val title = section.selectFirst("h2")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val cards = cards(section)
            if (cards.isEmpty()) null else ParsedSection(title, cards)
        }

    fun cards(root: Element): List<ParsedCard> =
        root.select(CARD_SELECTOR).mapNotNull { it.toCard() }.distinctBy { it.href }

    fun searchResults(doc: Document): List<ParsedCard> {
        val grouped = doc.select(".results-group").flatMap { cards(it) }
        // Some result pages render the grid without the wrapper.
        return (grouped.ifEmpty { cards(doc) }).distinctBy { it.href }
    }

    private fun Element.toCard(): ParsedCard? {
        val href = (if (tagName() == "a") attr("href") else selectFirst("a[href]")?.attr("href"))
            ?.trim()?.ifBlank { null } ?: return null
        // Listings link to /serie/<slug>; anything else on the page is navigation.
        if (!href.contains("/serie/")) return null

        val img = selectFirst("img")
        val title = img?.attr("alt")?.trim()?.ifBlank { null }
            ?: selectFirst("h6, h5, h3, h2, .card-title, .show-title")?.text()?.trim()?.ifBlank { null }
            ?: attr("title").trim().ifBlank { null }
            ?: return null

        return ParsedCard(title, href, Images.from(img))
    }

    // --- detail --------------------------------------------------------------------------

    fun seasonHrefs(doc: Document): List<String> =
        doc.select("#season-nav ul > li a[href]")
            .map { it.attr("href").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun seasonNumber(href: String, linkText: String): Int =
        linkText.trim().toIntOrNull()
            ?: Regex("/staffel-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0 // "Filme" and other specials sort ahead of season 1

    fun meta(doc: Document): ParsedMeta? {
        val scope = doc.selectFirst(".show-header-wrapper .container-fluid > div") ?: doc
        val title = scope.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: return null

        // The first <img> in the header is the backdrop, not the cover — pick by alt so a
        // reordering of the markup cannot silently swap the two.
        val images = scope.select("img")
        val backdrop = images.firstOrNull { it.attr("alt").equals("Backdrop", ignoreCase = true) }
        val cover = images.firstOrNull { it !== backdrop }

        return ParsedMeta(
            title = title,
            poster = Images.from(cover),
            plot = scope.select(".description-text").text().trim().ifBlank { null },
            year = scope.selectFirst("h1 + p a[href*=/jahr/]")?.text()?.trim()?.take(4)?.toIntOrNull()
                ?: scope.selectFirst("h1 + p > a")?.text()?.trim()?.take(4)?.toIntOrNull(),
            tags = seriesGroup(scope, "Genre:"),
            actors = seriesGroup(scope, "Besetzung:").map { ParsedActor(it) },
            trailer = scope.selectFirst("button[data-trailer-url]")?.attr("data-trailer-url")
                ?.trim()?.ifBlank { null }
                ?: doc.selectFirst("button[data-trailer-url]")?.attr("data-trailer-url")
                    ?.trim()?.ifBlank { null },
        )
    }

    /** Backdrops make decent TV artwork and the header already carries one. */
    fun backdrop(doc: Document): String? = Images.from(
        doc.selectFirst(".backdrop-picture img")
            ?: doc.select("img").firstOrNull { it.attr("alt").equals("Backdrop", true) }
    )

    /** "FSK 12" out of the metadata line under the title, or null when unrated. */
    fun ageRating(doc: Document): String? =
        Regex("FSK\\s*\\d+").find(doc.selectFirst("h1 + p")?.text().orEmpty())?.value

    /** Values of one `li.series-group` block, e.g. "Genre:" -> ["Science Fiction", "Drama"]. */
    private fun seriesGroup(scope: Element, label: String): List<String> =
        scope.select("li.series-group:contains($label) a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

    // --- episodes ------------------------------------------------------------------------

    /**
     * Episode rows, including the per-episode language flags and hoster icons.
     *
     * The flags here are real availability, not a static legend: `/serie/silo` shows
     * german+english while `/serie/18if` shows english+english-german and no german at all.
     * That makes this the cheapest language signal in the repo — no extra request per episode,
     * unlike the aniworld family where the same information only exists on the episode page.
     */
    fun episodes(doc: Document): List<ParsedEpisode> =
        doc.select(".episode-section .episode-row, tr.episode-row").mapNotNull { row ->
            // Rows navigate from an onclick handler; accept a plain href too should the
            // markup ever gain one.
            val href = Regex("['\"](/[^'\"]+)['\"]").find(row.attr("onclick"))?.groupValues?.get(1)
                ?: row.selectFirst("a[href]")?.attr("href")?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            val number = row.selectFirst(".episode-number-cell")?.text()?.trim()?.toIntOrNull()
                ?: Regex("/episode-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()

            val title = row.select(".episode-title-cell > *")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" - ")
                .ifBlank { row.selectFirst(".episode-title-cell")?.text()?.trim() }
                ?.ifBlank { null }

            ParsedEpisode(
                href = href,
                number = number,
                title = title,
                languages = languagesOf(row),
                hosters = row.select("img.watch-link[alt]")
                    .map { it.attr("alt").trim() }
                    .filter { it.isNotEmpty() }
                    .distinct(),
            )
        }

    private fun languagesOf(row: Element): Set<SourceLanguage> =
        row.select("svg.watch-language, .episode-language-cell svg")
            .flatMap { svg -> svg.classNames() }
            .mapNotNull { cls -> cls.removePrefix("svg-flag-").takeIf { it != cls } }
            .map { SourceLanguage.fromFlagClass(it) }
            .filter { it != SourceLanguage.Unknown }
            .toSet()

    // --- play buttons --------------------------------------------------------------------

    data class PlayButton(val url: String, val language: SourceLanguage, val hoster: String?)

    fun playButtons(doc: Document): List<PlayButton> =
        doc.select("button[data-play-url]")
            .mapNotNull { btn ->
                val url = btn.attr("data-play-url").trim().ifBlank { null } ?: return@mapNotNull null
                PlayButton(
                    url = url,
                    language = SourceLanguage.fromLabel(btn.attr("data-language-label")),
                    hoster = hosterName(btn.attr("data-provider-name")),
                )
            }
            .distinctBy { it.url }

    /**
     * Some buttons carry the literal placeholder "Provider" instead of a host name. Using it
     * as a source caption would replace the real host the extractor reports — so it is
     * dropped and the extractor's own name wins.
     */
    private fun hosterName(raw: String): String? = raw.trim()
        .takeIf { it.isNotEmpty() && !it.equals("Provider", ignoreCase = true) }

    /** Pre-2026 markup, still served by sibling sites in this family. */
    fun legacyHosters(doc: Document): List<PlayButton> =
        doc.select("li[data-link-target]")
            .mapNotNull { li ->
                val url = li.attr("data-link-target").trim().ifBlank { null } ?: return@mapNotNull null
                PlayButton(url, SourceLanguage.fromAniWorldKey(li.attr("data-lang-key")), null)
            }
            .distinctBy { it.url }
}
