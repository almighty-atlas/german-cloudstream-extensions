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

## Provider status

| # | Site | Folder | Status |
|---|------|--------|--------|
| 1 | aniworld.to | `AniWorld/` | ✅ done (v12), tested on TV |
| 2 | serienstream.to (s.to) | `SerienStream/` | 🧪 v2, Selektoren live verifiziert, **auf TV ungetestet** |
| 3 | bs.to | – | ⏳ next |
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
- Play: `button[data-play-url]` + `data-provider-name` / `data-language-label`.
  **Verifiziert am 2026-08-29 gegen die Live-Site.** Die Sprachwerte sind `Deutsch` (Dub),
  `Ger-Sub` und `Englisch` — nicht ausgeschriebene Formen wie "Deutsch (Untertitel)". Die
  abgekürzte `Ger-`-Form muss die Sprachheuristik mit abdecken.
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

Nicht verifiziert: welcher davon *deine* konkreten Fehler auslöst — die Site ist aus der
Build-Sandbox nicht erreichbar (Netzwerk-Policy blockt Streaming-Domains).

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
