package com.germanstreams.common

import com.lagradost.cloudstream3.app

/**
 * Shared HTTP defaults.
 *
 * Every site in this repo sits behind a bot filter of some kind (DDoS-Guard on the
 * aniworld/serienstream family, a stricter UA check on filmo). Sending the same desktop
 * client everywhere keeps the markup consistent and avoids the mobile layouts, whose
 * selectors differ from the ones the parsers are written against.
 */
object Net {
    const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64; rv:152.0) Gecko/20100101 Firefox/152.0"

    /** Seconds. Without this a dead hoster stalls loadLinks until NiceHttp's own default. */
    const val TIMEOUT = 20L

    val browserHeaders = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Accept-Language" to "de-DE,de;q=0.9,en;q=0.8",
    )

    /**
     * Resolves a play/redirect endpoint to the hoster URL **without walking the chain**.
     *
     * Following the redirects lands on whatever the hoster serves a request that arrives
     * with no referer of its own, which is usually an anti-bot stub rather than an embed.
     * Reading the Location header keeps our referer in play for the extractor that runs next.
     *
     * A relative Location is resolved against the request URL — some of these endpoints
     * answer with a bare path.
     */
    suspend fun resolveRedirect(
        url: String,
        referer: String,
        cookies: Map<String, String> = emptyMap(),
    ): String? = runCatching {
        val head = app.get(
            url,
            referer = referer,
            headers = browserHeaders,
            cookies = cookies,
            allowRedirects = false,
            timeout = TIMEOUT,
        )
        val location = head.headers["location"]?.ifBlank { null }
        if (location != null) return@runCatching absolutize(location, url)

        // No Location: either the endpoint answered 200 (an interstitial, handled by the
        // caller) or it needs the full chain. Only then pay for a second request.
        app.get(url, referer = referer, headers = browserHeaders, cookies = cookies, timeout = TIMEOUT)
            .url
            .takeIf { it != url && it.isNotBlank() }
    }.getOrNull()

    /** Turns a possibly relative URL into an absolute one, relative to [base]. */
    fun absolutize(url: String, base: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url
        else runCatching { java.net.URL(java.net.URL(base), url).toString() }.getOrDefault(url)
}
