# Dev Notes

Working notes for developing these CloudStream provider plugins. Read this first when resuming.

## Repo / build / install

- **Repo:** `github.com/almighty-atlas/german-cloudstream-extensions`
- **Push:** SSH only. Key `~/.ssh/homelab` is passphrase-protected → must be loaded into the
  ssh-agent first (`ssh-add ~/.ssh/homelab`) before any push. Remote is the `git@github.com:` URL.
- **CI:** push to `main` → GitHub Actions builds every provider → `.cs3` + `plugins.json` land on
  the `builds` branch. Build takes ~2–3 min.
- **Build status without the web UI:**
  - Runs: `https://api.github.com/repos/almighty-atlas/german-cloudstream-extensions/actions/runs?per_page=1`
  - On failure, the workflow tees the gradle output to `build.log` on the `builds` branch
    (`git fetch origin builds && git show origin/builds:build.log`). Grep for `^e: `.
- **Install in app (Android TV):** Settings → Extensions → Add repository →
  `https://raw.githubusercontent.com/almighty-atlas/german-cloudstream-extensions/main/repo.json`
- **Updates:** bump `version` in each provider's `build.gradle.kts` on every change, else the app
  won't offer an update. App must be **restarted** to reload an updated plugin. CloudStream also
  caches `load()` results — re-open / refresh a title to see load changes.
- **Domain-Umzug:** alle drei Provider setzen `canBeOverridden = true`. Zieht eine Site um,
  kann der User die URL in den Provider-Einstellungen der App selbst überschreiben — es braucht
  keinen neuen Build. Bewusst **keine** eingebaute Mirror-Liste: unverifizierte Ausweichdomains
  wären eine Einladung für Squatter/Klone.

## Architektur

Gemeinsamer Code liegt in `common/src/main/kotlin/com/germanstreams/common/` und wird über
`sourceSets` in **jeden** Provider einkompiliert (siehe root `build.gradle.kts`) — kein eigenes
Gradle-Modul. Grund: jede `.cs3` ist ein eigenständiges Dex, der Code muss also ohnehin in jedes
Plugin; ein echtes Modul würde zusätzlich eine Junk-`.cs3` erzeugen. Verifiziert: nach `./gradlew
make` enthält jedes `classes.dex` die `com/germanstreams/common`-Klassen.

| Datei | Zweck |
|---|---|
| `Net.kt` | Desktop-UA, Timeout, `resolveRedirect` (Location statt Redirect-Kette), `absolutize` |
| `SourceLanguage.kt` | Dub / Ger-Sub / Eng-Sub + Parser für alle drei Markup-Varianten |
| `SourceCollector.kt` | die komplette `loadLinks`-Pipeline (siehe unten) |
| `EpisodeLanguageCache.kt` | Prozess-Memo für AniWorlds Episoden-Sprachen |
| `parse/*Parser.kt` | Selektoren, **frei von CloudStream-Typen** |

Dass die Parser keine CloudStream-Typen kennen, ist Absicht: nur so laufen sie als normale
JVM-Unit-Tests. Sie liefern rohe hrefs; `fixUrl` bleibt Sache des Providers.

**Die vier `loadLinks`-Regeln stehen jetzt im Code, nicht mehr nur in der Doku.**
`SourceCollector` erzwingt sie: Referer ist immer der Site-Root, Redirects werden über den
`location`-Header gelesen, `emitTo` liefert `false` wenn nichts gefunden wurde, und im
Extractor-Callback läuft nichts Suspendierendes. Bei einem neuen Provider reicht es,
`SourceCollector` zu benutzen — vergessen kann man die Regeln dann nicht mehr.

Zusätzlich dedupliziert `emitTo` nach URL: derselbe Hoster-Link unter zwei Sprach-Buttons ist
eine Quelle, nicht zwei.

## Tests und Selektor-Wächter

Selektor-Rot ist der eigentliche Fehlermodus dieses Repos, und ein grüner Build sagt darüber
nichts. Deshalb zwei Stufen:

- **Fixture-Tests** (`*/src/test/`) laufen offline gegen eingecheckte Seiten-Captures unter
  `*/src/test/resources/fixtures/`. Sie laufen in CI **vor** dem Löschen der alten `.cs3` —
  ein roter Test lässt die bisher veröffentlichten Plugins also stehen, statt sie durch nichts
  zu ersetzen. Captures sind getrimmt (Scripts, Styles, SVG-Geometrie, alle `srcset`-Kandidaten
  bis auf den ersten); alles was ein Selektor anfasst, ist unverändert.
- **Live-Smoke-Test** (`*LiveTest.kt`, nur mit `SMOKE=1`) zieht die echten Seiten und prüft, ob
  die Selektoren dort noch greifen. Läuft nächtlich über `.github/workflows/selector-smoke.yml`
  und öffnet bei Bruch ein Issue mit Label `selector-rot` (ein Issue pro Ausfall, danach nur
  noch Kommentare).

Wenn eine Site umgebaut wird, **sollen** diese Tests rot werden: Fixture neu ziehen, Selektoren
nachziehen, Version hochzählen.

AniWorld ist davon ausgenommen — die Site antwortet aus CI wie aus der Sandbox auf jedem Pfad
403 (DDoS-Guard). Dessen Tests laufen gegen **synthetische** Fixtures und sichern nur gegen
versehentliches Refactoring, nicht gegen ein Redesign. Das ist im Test auch so dokumentiert.

## CI gotchas (already fixed, keep in mind)

- Kotlin: root `build.gradle.kts` pins `kotlin-gradle-plugin:2.3.0` to match the
  `cloudstream:pre-release` stub (template's 2.1.0 made every cloudstream symbol "unresolved").
- `apmap` is deprecated-as-ERROR → use `amap`.
- `tryParseJson` / `toJson` live in `com.lagradost.cloudstream3.utils.AppUtils`; `toJson()` is a
  receiver-call (`obj.toJson()`), not `toJson(obj)`.
- `ExtractorLink` is an `open class`, `name` is a `val`, no `copy()` → rebuild via
  `newExtractorLink(source, name, url, type) { ... }` (suspend).

## CloudStream behaviour learned

- Source order in the player = `getLinkPriority = quality.defaultPriority(0–8) + sourcePriority(=1
  default)`. Quality-primary, stable sort. Language cannot be the primary sort key via emit order.
- Season spinner is a **union of Dub+Sub seasons** (`ResultViewModel2`), so you cannot hide a
  dub-less season when "Dub" is selected. Annotate season/episode names instead.
- `DubStatus` is only `Dubbed` / `Subbed` (no 3-way). `AnimeSearchResponse.addDubStatus(dubExist,
  subExist)` renders Dub/Sub chips on cards. `EpisodeResponse.addSeasonNames(List<SeasonData>)`
  names seasons in the spinner.

## Namenskonvention

Eigene Provider tragen ein **★-Suffix** im `MainAPI.name` (`"AniWorld ★"`, `"SerienStream ★"`),
damit sie in Suchergebnissen und der Quellenliste von gleichnamigen Providern aus anderen Repos
(z. B. Bnyro/GermanProviders) unterscheidbar sind. Die `description` trägt zusätzlich ein
`★ Eigene Version —`-Präfix für den Extensions-Manager.

**Achtung bei Änderungen an `MainAPI.name`:** der Wert wird als `apiName` in
`DataStoreHelper.BookmarkedData` und `ResumeWatchingResult` gespeichert. Eine spätere Umbenennung
entkoppelt vorhandene Lesezeichen und den Watch-Fortschritt vom Provider. Der Name sollte ab
jetzt stabil bleiben.

## Quellen-Benennung und Qualität

Ein Hoster liefert pro Episode oft **mehrere Links unterschiedlicher Art**, nicht mehrere
Qualitätsstufen. Beim VOE-Extractor (dessen `name` intern nur `"Voe"` ist) entstehen daraus:

| Angezeigt | Was es ist | Qualität |
|---|---|---|
| `Voe 712p` | konkrete HLS-Variante aus der Master-Playlist | bekannt |
| `Voe MP4` | direkter MP4-Link | nicht ohne Laden der Datei ermittelbar |
| `Voe` | adaptive Master-Playlist | hat keine feste — Player wechselt zur Laufzeit |

Die Qualität lässt sich also **nicht für alle Links abgreifen**; bei den letzten beiden gibt es
schlicht keine. Krumme Werte wie `712p` sind echte Bildhöhen (Cinemascope-Crop) und liegen
neben dem `Qualities`-Enum (`P360/P480/P720/…`).

Manche Extractor-Links tragen die Auflösung nur im **Namen** und lassen `quality` leer — die
sortieren dann wie „unbekannt" ganz nach hinten. Beide Provider holen sie deshalb per
`getQualityFromName(...)` nach, wenn das Feld leer ist (bei Filmo zuerst aus dem Chip-Text,
der die Qualität ohnehin nennt).

### Namensschema

Einheitlich **`<Sprache> · <Hoster> · <Variante>`**, z. B. `🇩🇪 Deutsch · Voe · 712p`. Die
Variante entsteht aus `link.name` minus `link.source`; bleibt dabei nichts übrig, ist es die
adaptive Master-Playlist und wird als **`Auto`** ausgewiesen statt leer zu bleiben. Filmo hängt
die Variante nur an, wenn der Chip-Text sie nicht ohnehin schon nennt.

## Provider status

| # | Site | Folder | Status |
|---|------|--------|--------|
| 1 | aniworld.to | `AniWorld/` | ✅ v14, Request-Budget + Cache; Site aus Sandbox/CI nicht erreichbar |
| 2 | serienstream.to (s.to) | `SerienStream/` | 🧪 v6, Selektoren live + per Test verifiziert, **auf TV ungetestet** |
| 3 | filmo.to | `Filmo/` | 🧪 v4, Kette live + per Test verifiziert, **auf TV ungetestet** |
| 4 | bs.to | – | ⏳ next |
| 4 | anime-loads.org | – | ⏳ next |
| 5 | www21.kinox.to | – | ⏳ movie family |
| 6 | movie4k.sx | – | ⏳ movie family |
| 7 | movie2k.cx | – | ⏳ movie family |
| 8 | megakino3.tv | – | ⏳ movie family |
| 9 | moflix-stream.xyz | – | ⏳ unique |
| 10 | kinoger.com | – | ⏳ unique |
| 11 | filmpalast.to | – | ⏳ unique |
| 12 | chillflix.to | – | ⏳ unique |
| 13 | cineby.at | – | ⏳ unique |
| 14 | kinoking.cc | – | ⏳ unique |
| 15 | kinos.to | – | ⏳ unique |
| 16 | aether.bar | – | ⏳ unique |
| 17 | streamcloud.my | – | ⏳ unique |
| 18 | streamkiste.taxi | – | ⏳ unique |
| 19 | einschalten.in | – | ⏳ unique |
| 20 | haschcon.com | – | ⏳ unique |

## SerienStream (s.to) site facts

**Wichtig: s.to ist KEIN AniWorld-Klon mehr.** Die beiden Sites sind auseinandergelaufen —
serienstream.to wurde Anfang 2026 redesignt, aniworld.to serviert weiter das alte Layout.
Belegt durch Bnyro/GermanProviders: dessen `Aniworld` nutzt die alten Selektoren
(`div.coverListItem`, `div.seriesCoverBox`), sein `Serienstream` wurde am 2026-01-29 mit
"rewrite for compatibility with new website layout" komplett neu geschrieben.

- Domain: `serienstream.to` (Bnyro-Commit 2026-07-07 "switch to serienstream.to domain").
  s.to leitet dorthin. Bei einem erneuten Umzug reicht `mainUrl` im Provider.
- Suche: `GET /suche?term=X&tab=shows` (HTML, kein JSON-Endpoint mehr) → `.results-group .card`
- Katalog: `/beliebte-serien` → Sektionen `.popular-page > div` (h2 + `a.show-card`)
- Detail: `.show-header-wrapper .container-fluid > div`; Staffeln `#season-nav ul > li a`
- Episodenzeilen: `.episode-section .episode-row`, Ziel-URL steckt im **`onclick`**, nicht in href
- Play: `button[data-play-url]` + `data-provider-name` / `data-language-label` /
  `data-language-id`. **Verifiziert am 2026-08-29 und erneut am 2026-08-30.** Die Sprachwerte
  sind `Deutsch` (Dub), `Ger-Sub` und `Englisch` — nicht ausgeschriebene Formen wie
  "Deutsch (Untertitel)". Die abgekürzte `Ger-`-Form muss die Sprachheuristik mit abdecken.
  **`data-provider-name` ist teils der Platzhalter `"Provider"`** statt eines Hosternamens —
  der wird verworfen, sonst überschreibt er den echten Hoster aus dem Extractor.

### Episodenzeilen tragen die echte Sprachverfügbarkeit (2026-08-30 verifiziert)

Das ist die wichtigste Erkenntnis dieser Runde und war vorher ungenutzt. Jede `tr.episode-row`
enthält neben Titel und Ziel-URL:

- `td.episode-language-cell svg.watch-language.svg-flag-<x>` — die verfügbaren Sprachen
- `td.episode-watch-cell img.watch-link[alt]` — die angebotenen Hoster (z. B. `VOE`)

| Klasse | Bedeutung |
|---|---|
| `svg-flag-german` | Deutsch (Dub) |
| `svg-flag-english-german` | Ger-Sub (dt. UT auf engl. Ton) |
| `svg-flag-english` | Englisch |

**Das ist keine statische Legende wie bei AniWorld** — die Flags variieren zwischen Serien:
`/serie/silo` zeigt `german` + `english`, `/serie/18if` zeigt `english` + `english-german` und
**kein** `german`. Belegt durch den Test
`episode language flags distinguish a dubbed show from a subbed one`, der genau gegen diese
beiden Fixtures läuft.

Heißt: SerienStream bekommt 🇩🇪-Flags pro Episode und Staffel **ohne einen einzigen
Extra-Request** — genau das, wofür AniWorld einen Request pro Episode zahlen muss.

### Weiteres auf der Detailseite (2026-08-30)

- Cover ist **lazy-loaded**: `src` ist ein base64-1×1-GIF, die echte URL steht in `data-src` /
  `data-srcset`. `Images.from(...)` prüft deshalb in dieser Reihenfolge und verwirft `data:`-URIs.
- Der **erste** `<img>` im Header ist der Backdrop (`alt="Backdrop"`), nicht das Cover — nach
  `alt` unterscheiden, sonst landet der Backdrop als Poster. Der Backdrop wird jetzt als
  `backgroundPosterUrl` genutzt.
- FSK steht in der Zeile unter dem `h1` (`h1 + p`, z. B. „FSK 12") → `contentRating`.
- Jahr: `h1 + p a[href*=/jahr/]`. Genres/Besetzung: `li.series-group:contains(Genre:|Besetzung:) a`.
- **Keine** Empfehlungen/„Ähnliche Serien" auf der Detailseite — gesucht und nicht vorhanden.
- Es gibt eine Bewertungs*anzahl* („1.063 Bewertungen"), aber **keinen Durchschnittswert** →
  kein `score`.

### Katalog-Endpoints (2026-08-30 verifiziert)

- `/genre/{slug}?page=N` — paginiert, ~60 Seiten à 30 Titel. Slugs u. a. `action`, `comedy`,
  `drama`, `science-fiction`, `mystery`, `anime`, `zeichentrick`.
- `/serien` — kompletter A–Z-Index, **10.865 Titel in einer 2,5-MB-Seite**. Zu schwer für eine
  Home-Row, deshalb nicht eingebunden.
- `/serienkalender` enthält keine `/serie/`-Links und taugt so nicht als Row.
- `data-play-url` ist `/r?t=<Laravel-Token>`, nicht `/redirect/{id}` wie bei AniWorld.
  **Von der Sandbox aus nicht auflösbar** (DDoS-Guard: 403 bzw. "Checking your browser").
  Ob der Endpoint per `location`-Header oder erst nach der Redirect-Kette auflöst, ist damit
  offen — `loadLinks` deckt beide Fälle ab.
- Die Site steht hinter **DDoS-Guard** (`__ddg8_`/`__ddg9_`/`__ddg10_`-Cookies). Normale Seiten
  gehen durch, der Play-Endpoint nicht.

### Warum Bnyros Serienstream Link-Fehler produziert

Vier Schwächen in dessen `loadLinks`, die im eigenen Provider behoben sind:

1. **Falscher Referer:** `loadExtractor(url, data, ...)` übergibt die Episoden-URL. Hoster
   (voe, filemoon, vidmoly) prüfen gegen den Site-Root → `$mainUrl/`.
2. **Redirect wird durchlaufen:** `app.get(streamUrl).url` folgt der Kette bis zum Ende und
   landet auf dem, was der Hoster einer refererlosen Anfrage ausliefert (oft Anti-Bot-Stub).
   Stattdessen `allowRedirects = false` + `location`-Header lesen; voller GET nur als Fallback.
3. **`return true` immer** — auch bei null gefundenen Quellen. CloudStream meldet dann Erfolg
   und zeigt nichts. Richtig ist `sources.isNotEmpty()`.
4. **`runBlocking` im Extractor-Callback**, der auf dem Main-Dispatcher laufen kann. Stattdessen
   Links erst sammeln, dann außerhalb via `newExtractorLink` neu bauen.

Alle vier sind inzwischen in `common/.../SourceCollector.kt` zentralisiert, damit sie bei
einem neuen Provider nicht wieder einzeln vergessen werden können.

Nicht verifiziert: welcher davon *deine* konkreten Fehler auslöst — der Play-Endpoint
`/r?t=<token>` ist aus der Sandbox nicht auflösbar (DDoS-Guard: 403 bzw.
„Checking your browser"). Die normalen Seiten gehen durch.

## Filmo (filmo.to) site facts

Reine **Film**-Seite (`/movies/...`, keine Serien). Alle Selektoren am 2026-08-29 gegen die
Live-Site verifiziert. Braucht einen Desktop-User-Agent, sonst weicht das Markup ab.

- Katalog: `/popular` → Sektionen `section.popular-spotlight` + `div.video-row`, Überschrift `h3`
- Karten: `.popular-spotlight-card__link` (Titel im **`h4`**) und `a.video-card` (Titel im
  **`img[alt]`**). Bnyros `[class*=title]` liefert hier **leer** — nicht übernehmen.
- Suche: `GET /search?q=X` → `section.search-top-results article > a`, Titel `[class*=__title]`
- Detail: `.primary-container h1`, `p.movie-detail-synopsis`, `img.ft-packshot-meta`,
  Metadaten als `div.details-group dl` (dt/dd) mit deutschen Labels: `Erscheinungsdatum`,
  `Laufzeit`, `Bewertung`, `Originalsprache`, `Länder`, `Genres`, `Regie`, `Darsteller`
- Listing `/movies?page=N` — 160 Seiten à 42 Filme (~6.700). `/collections/{slug}?page=N`
  ebenso. Letzte Seite steht im Pager (`FilmoParser.lastPage`), damit das Blättern endet.

### Was auf der Detailseite bisher ungenutzt lag (2026-08-30)

- `Bewertung` = `"8.0 / 10 (12,017 Stimmen)"` → `Score.from10(8.0)`. Der Regex nimmt nur den
  Wert vor `/ 10`, nicht die Stimmenzahl.
- `Laufzeit` = `"181 Min."` auf der Detailseite, aber `"2 h 31 min"` in den Card-Overlays —
  `durationMinutes` deckt beide Formen ab (und `"1 Std. 52 Min."`).
- Trailer: YouTube-Link im Modal (`a[href*=youtube.com/watch]`) → `addTrailer`.
- **„Verwandte Filme"** = `div.swiper-wrapper a.video-card` → `recommendations`. Auf der Seite
  gibt es genau **einen** `swiper-wrapper`, das ist also eindeutig; die 12 `card-details`-Panels
  enthalten keine verschachtelten Karten.
- Darsteller verlinken auf `/people/{slug}` → `Actor(name, image)` statt nackter Strings.

### Link-Auflösung (zweistufig, mit Einmal-Token)

1. Filmseite laden — liefert das **`XSRF-TOKEN`-Cookie**, das Schritt 2 braucht. Cookies müssen
   über alle folgenden Requests mitgeführt werden.
2. Pro `.provider-chip[data-p]`: `POST /n` mit `{"p": <data-p>}`, Header `X-XSRF-TOKEN`
   (Cookie-Wert, `%3D` → `=`) → Antwort `{"x": "<slug>"}`
3. `GET /n/{slug}` → die Hoster-URL

**Der Slug ist ein Einmal-Token: ein zweiter Abruf antwortet 404.** Also nur *ein* Request pro
Slug — kein Retry, kein „erst Location prüfen, dann nochmal voll laden". Beide Ausgänge aus
derselben Antwort bedienen (`allowRedirects = false`):

| Hoster | Antwort | Wo die URL steckt |
|---|---|---|
| VOE | `302` | `location`-Header |
| Byse | `200` | Interstitial-Seite, einziger externer `<a href>` |

Der Chip-Text (`"VOE WEB-DL 720p"`) trägt Hoster + Release + Qualität und ist als Quellenname
informativer als der Extractor-Default.

## AniWorld site facts (reference for the s.to/bs.to family)

- Search: `GET /ajax/seriesSearch?keyword=X` → JSON `[{name, link, cover, productionYear}]`
- Detail: `/anime/stream/{slug}`; seasons `.../staffel-N` + `/filme`; episode `.../staffel-N/episode-M`
- Season table: `table.seasonEpisodesList td.seasonEpisodeTitle a` (`<strong>`=DE title, `<span>`=EN).
  The language flags on the season page are a **static legend** (always all 3) — NOT availability.
- Real per-episode languages: the episode page hosters `li[data-link-target][data-lang-key]`
  (`1`=German Dub, `2`=English Sub, `3`=German Sub).
- Play: `/redirect/{id}` 301 → real hoster (voe.sx, doodstream, filemoon, vidmoly…) → `loadExtractor`.
- Home rows: `/beliebte-animes` + `/neu` use `.col-md-15` card wrappers (no flags);
  `/neue-episoden` rows DO carry accurate per-episode flags (`/public/img/german.svg` = dub).

### Request-Budget (2026-08-30)

Sprachverfügbarkeit existiert hier **nur** auf der Episodenseite, kostet also einen Request pro
Episode. Der alte Stand tat das bedingungslos: One Piece (~1150 Episoden) bedeutete ~1150 GETs
beim bloßen Öffnen der Detailseite, dazu ~60 Requests allein zum Zeichnen des Home-Screens
(ein Probe pro Karte). Auf einer DDoS-Guard-Site ist das nicht nur langsam, sondern der
schnellste Weg in eine Sperre.

Jetzt:

- **Home-Screen probt gar nicht mehr.** Stattdessen ist `/neue-episoden` als Row aufgenommen —
  die einzige Liste, deren Zeilen das Dub-Flag ohnehin tragen.
- **`load()` hat ein Budget von 60 Requests.** Darunter wird jede Episode exakt aufgelöst wie
  bisher; darüber werden pro Staffel 3 Episoden gesampelt und das Ergebnis auf die Staffel
  angewandt (Staffel-Label bleibt korrekt, Pro-Episode-Flag wird zur Näherung).
- **`EpisodeLanguageCache`** merkt sich Ergebnisse prozessweit — erneutes Öffnen einer Serie
  kostet nichts. `loadLinks` füttert den Cache mit, weil es dieselbe Seite ohnehin lädt.
- Batchgröße von 20 auf **8** gesenkt.

Die Zahlen (60 / 3 / 8) sind Konstanten oben in `AniWorldProvider` und bewusst konservativ —
sie sind **nicht** gegen die Live-Site kalibriert, weil die aus Sandbox und CI 403 liefert.
