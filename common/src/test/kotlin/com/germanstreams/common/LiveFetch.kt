package com.germanstreams.common

import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal fetcher for the live-site smoke tests.
 *
 * Those tests answer the one question a fixture cannot: does the site *still* look like the
 * capture? They only run when `SMOKE=1` is set (the nightly workflow does), so an ordinary
 * `./gradlew test` stays offline and deterministic.
 *
 * This deliberately does not use NiceHttp: `app` is a CloudStream singleton that expects an
 * Android runtime, and the point here is to stay on the plain JVM.
 */
object LiveFetch {

    /** True when the caller asked for live checks. Everything else skips itself. */
    val enabled: Boolean get() = System.getenv("SMOKE") == "1"

    fun html(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", Net.DESKTOP_UA)
            setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        connection.inputStream.use { stream ->
            val body = stream.readBytes().toString(Charsets.UTF_8)
            check(connection.responseCode == 200) {
                "$url answered ${connection.responseCode}"
            }
            return body
        }
    }
}
