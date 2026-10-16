package com.germanstreams.common

import java.net.URI

/**
 * Session-local positive language observations for AniWorld anime.
 *
 * Only already-fetched feed, season or episode data enters the index. A miss never triggers
 * an HTTP request, and missing languages never prove an unavailable track. A bounded cache
 * keeps one browsing session from growing without limit; persistence/TTL is a later phase.
 */
object AniWorldLanguageIndex {
    private const val MAX_ANIME = 1000
    private val animePath = Regex("^(/anime/stream/[^/]+)(?:/.*)?$")
    private val entries = LinkedHashMap<String, Set<SourceLanguage>>()

    fun animeKey(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme != null && uri.scheme.lowercase() !in setOf("http", "https")) return null
        if (uri.scheme != null && uri.host.isNullOrBlank()) return null
        return animePath.matchEntire(uri.path ?: "")?.groupValues?.get(1)
    }

    @Synchronized
    fun observe(href: String, languages: Set<SourceLanguage>) {
        if (languages.isEmpty()) return
        val key = animeKey(href) ?: return
        val merged = entries.remove(key).orEmpty() + languages
        if (entries.size >= MAX_ANIME) entries.remove(entries.keys.first())
        entries[key] = merged
    }

    @Synchronized
    fun languages(href: String): Set<SourceLanguage> =
        animeKey(href)?.let { entries[it] }.orEmpty()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}
