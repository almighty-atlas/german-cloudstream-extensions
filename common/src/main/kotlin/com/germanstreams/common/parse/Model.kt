package com.germanstreams.common.parse

import com.germanstreams.common.SourceLanguage

/**
 * Plain results shared by the site parsers.
 *
 * These carry raw hrefs exactly as the page states them — resolving them against the site
 * root is `MainAPI.fixUrl`'s job and needs a provider. Keeping that out of here is what lets
 * the parsers run as ordinary JVM unit tests against checked-in fixtures.
 */
data class ParsedCard(
    val title: String,
    val href: String,
    val poster: String?,
)

data class ParsedSection(
    val title: String,
    val cards: List<ParsedCard>,
)

data class ParsedEpisode(
    val href: String,
    val number: Int?,
    val title: String?,
    /** Empty when the listing carries no language information. */
    val languages: Set<SourceLanguage> = emptySet(),
    /** Hoster names advertised for the episode, e.g. ["VOE"]. Empty when unknown. */
    val hosters: List<String> = emptyList(),
) {
    val hasGermanDub: Boolean get() = SourceLanguage.GermanDub in languages
}

data class ParsedMeta(
    val title: String,
    val poster: String? = null,
    val plot: String? = null,
    val year: Int? = null,
    val tags: List<String> = emptyList(),
    val actors: List<ParsedActor> = emptyList(),
    val trailer: String? = null,
    /** 0..10 as stated by the site, or null when it states none. */
    val rating: Double? = null,
    val durationMinutes: Int? = null,
    val recommendations: List<ParsedCard> = emptyList(),
)

data class ParsedActor(
    val name: String,
    val image: String? = null,
    val role: String? = null,
)
