// Use an integer for version numbers
version = 3

cloudstream {
    description = "★ Eigene Version — SerienStream (s.to), Serien auf Deutsch"
    authors = listOf("heavenshallburn")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("TvSeries")
    language = "de"

    iconUrl = "https://serienstream.to/favicon.ico"
}

android {
    namespace = "com.germanstreams.serienstream"
}
