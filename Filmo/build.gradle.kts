// Use an integer for version numbers
version = 3

cloudstream {
    description = "★ Eigene Version — Filmo, Filme auf Deutsch"
    authors = listOf("heavenshallburn")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Movie")
    language = "de"

    iconUrl = "https://filmo.to/favicon.ico"
}

android {
    namespace = "com.germanstreams.filmo"
}
