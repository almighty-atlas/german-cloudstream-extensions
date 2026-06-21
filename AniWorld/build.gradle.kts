// Use an integer for version numbers
version = 8

cloudstream {
    description = "AniWorld – Anime auf Deutsch (Sub & Dub)"
    authors = listOf("heavenshallburn")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "de"

    iconUrl = "https://aniworld.to/favicon.ico"
}

android {
    namespace = "com.germanstreams.aniworld"
}
