package com.germanstreams.common

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide memo for "which languages does this episode page offer".
 *
 * Only aniworld needs this: its language data lives on the episode page and nowhere else, so
 * determining it costs one request per episode. Without a memo, re-opening a series pays that
 * cost again in full — and on a site behind DDoS-Guard, repeating hundreds of requests is a
 * good way to get the client blocked rather than merely slowed down.
 *
 * Entries stay valid for the life of the process: an episode gaining a dub mid-session is
 * rare enough that a stale flag beats another request storm, and restarting the app (which
 * CloudStream requires after a plugin update anyway) clears it.
 */
object EpisodeLanguageCache {

    /** Bounded so a long session browsing many long series cannot grow it without limit. */
    private const val MAX_ENTRIES = 4000

    private val entries = ConcurrentHashMap<String, Set<SourceLanguage>>()

    operator fun get(url: String): Set<SourceLanguage>? = entries[url]

    fun put(url: String, languages: Set<SourceLanguage>) {
        // Cheapest sane eviction: drop everything once the cap is hit. The alternative is an
        // access-ordered map behind a lock, which is not worth it for a lookup this cheap.
        if (entries.size >= MAX_ENTRIES) entries.clear()
        entries[url] = languages
    }

    fun size(): Int = entries.size

    fun clear() = entries.clear()
}
