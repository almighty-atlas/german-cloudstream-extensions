# CLAUDE.md

CloudStream-Provider-Plugins (`.cs3`) für deutschsprachige Streaming-Seiten.

**Zuerst [`DEVNOTES.md`](DEVNOTES.md) lesen** — dort stehen Site-Fakten (Selektoren, Endpoints),
der Provider-Status und die gesammelten CloudStream-Eigenheiten. Neue Erkenntnisse gehören
dorthin, nicht hierher.

## Sprache

- Antworten an den User: **Deutsch**.
- Code, Kommentare, Commit-Messages: **Englisch**.

## Was in dieser Sandbox nicht geht

Nicht versuchen, es kostet nur Zeit — die Netzwerk-Policy blockt es:

- **Alle Streaming-Sites** (`aniworld.to`, `serienstream.to`, …) → kein Live-Scraping, keine
  Selektor-Verifikation von hier aus.
- **`dl.google.com` und `jitpack.io`** → kein Android SDK, keine cloudstream-Stubs, also
  **kein lokaler `./gradlew`-Build**.

Daraus folgt: Kompilierbarkeit wird ausschließlich über CI verifiziert, und Selektoren gar
nicht. Wenn Selektoren unsicher sind, **den User um den Seitenquelltext bitten statt zu raten**.
Vor dem Push hilft nur statische Prüfung — genutzte cloudstream-APIs gegen bereits
kompilierenden Code abgleichen (`AniWorld/`, oder Bnyros Repo).

## Build- und Testzyklus

- **Direkt auf `main` committen und pushen.** CI baut nur `main`/`master`; Feature-Branches
  erzeugen keine `.cs3` und bringen in diesem Solo-Repo nichts.
- **Version in `<Provider>/build.gradle.kts` bei jeder Änderung hochzählen** — sonst bietet die
  App kein Update an.
- Build dauert ~1,5–3 min. Status abfragen:
  ```
  curl -s "https://api.github.com/repos/almighty-atlas/german-cloudstream-extensions/actions/runs?per_page=1"
  ```
- Ergebnis liegt auf dem `builds`-Branch:
  ```
  git fetch origin builds && git ls-tree --name-only origin/builds
  git show origin/builds:build.log | grep '^e: '     # Kotlin-Fehler
  ```
- **Ein grüner Build heißt nur „kompiliert".** Ob die Selektoren stimmen, testet der User auf
  seinem Android TV. Das nie als „funktioniert" melden.

## Neue Provider

- **Site-Familien nicht als gleich annehmen.** aniworld.to und serienstream.to waren mal
  dieselbe Engine; s.to wurde Anfang 2026 redesignt, aniworld.to nicht. Jede Site einzeln prüfen,
  bevor ein bestehender Provider als Vorlage geklont wird.
- Beste Selektor-Referenz ist [Bnyro/GermanProviders](https://github.com/Bnyro/GermanProviders) —
  gegen die echten Sites verifiziert und meist aktueller als eigene Vermutungen:
  ```
  git clone --depth 1 https://github.com/Bnyro/GermanProviders /home/user/bnyro/germanproviders
  ```
  Dessen Selektoren übernehmen, dessen `loadLinks` **nicht** — siehe nächster Abschnitt.

## loadLinks: so und nicht anders

Diese vier Punkte sind die Ursache der meisten „Link-Fehler". Bei jedem Provider einhalten:

1. **Referer ist der Site-Root** (`"$mainUrl/"`), nicht die Episoden-URL. Hoster (voe, filemoon,
   vidmoly) liefern bei falschem Referer einen Anti-Bot-Stub statt Video.
2. **Redirect-Endpoints mit `allowRedirects = false`** aufrufen und den `location`-Header lesen.
   Die Kette durchlaufen zu lassen landet auf dem, was der Hoster einer refererlosen Anfrage gibt.
3. **`return sources.isNotEmpty()`** — nie unbedingtes `true`. Sonst meldet CloudStream Erfolg
   und zeigt nichts, was genau wie ein Ladefehler aussieht.
4. **Kein `runBlocking` im Extractor-Callback** (kann auf dem Main-Dispatcher hängen). Links erst
   sammeln, danach außerhalb mit `newExtractorLink` neu bauen.

`SerienStream/src/main/kotlin/com/germanstreams/SerienStreamProvider.kt` setzt alle vier um und
taugt als Vorlage.

## Kotlin-Fallen, die hier schon zugeschlagen haben

- `apmap` ist deprecated-as-ERROR → **`amap`**.
- `ExtractorLink` hat kein `copy()`; neu bauen via `newExtractorLink(source, name, url, type) { … }`.
- `if (a) { x } else { y }.foo()` bindet `.foo()` an den else-Zweig → **klammern**.
- Weitere in DEVNOTES unter „CI gotchas".
