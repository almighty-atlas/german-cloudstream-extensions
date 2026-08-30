# CLAUDE.md

CloudStream-Provider-Plugins (`.cs3`) für deutschsprachige Streaming-Seiten.

**Zuerst [`DEVNOTES.md`](DEVNOTES.md) lesen** — dort stehen Site-Fakten (Selektoren, Endpoints),
der Provider-Status und die gesammelten CloudStream-Eigenheiten. Neue Erkenntnisse gehören
dorthin, nicht hierher.

## Sprache

- Antworten an den User: **Deutsch**.
- Code, Kommentare, Commit-Messages: **Englisch**.

## Netzwerk und lokaler Build

Das Environment läuft auf `Custom` mit freigegebenen Domains. Verifiziert am 2026-08-29:

| Domain | Status |
|---|---|
| `dl.google.com`, `jitpack.io` | ✅ frei → **lokaler Gradle-Build möglich** |
| `serienstream.to` | ✅ frei → Selektoren live prüfbar |
| `aniworld.to` | ⚠️ erreichbar, Site antwortet 403 (Bot-Schutz, kein Proxy-Block) |
| `s.to` | ❌ nicht gelistet (egal, leitet auf serienstream.to) |

Root-URLs sind ein schlechter Test: `dl.google.com/` leitet auf `www.google.com` (nicht gelistet)
und sieht dann fälschlich gesperrt aus. Immer einen echten Pfad testen.

**Vor jedem Push lokal kompilieren** — das SDK ist nicht im Image, einmal pro Session einrichten:

```bash
curl -sS -o /tmp/cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p /home/user/android-sdk/cmdline-tools && unzip -q /tmp/cmdline.zip -d /home/user/android-sdk/cmdline-tools
mv /home/user/android-sdk/cmdline-tools/cmdline-tools /home/user/android-sdk/cmdline-tools/latest
yes | /home/user/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/home/user/android-sdk --licenses >/dev/null
/home/user/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/home/user/android-sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "sdk.dir=/home/user/android-sdk" > local.properties     # steht in .gitignore
export ANDROID_HOME=/home/user/android-sdk ANDROID_SDK_ROOT=/home/user/android-sdk
./gradlew :<Provider>:compileDebugKotlin --no-daemon
```

Bekannte, unkritische Warnung in allen Providern: *„Type annotation class 'Nullable' … is
inaccessible"* bei `selectFirst(...)?.let { it.attr(...) }`. Kommt vom Classpath (jsoups
Annotation fehlt im Stub), nicht vom Code — eine explizite Typannotation behebt sie **nicht**.
Ab Kotlin 2.4 wird sie zum Fehler; dann die Annotations-Dependency ergänzen.

### Was weiterhin nicht geht
- **Chromium/Playwright kommt nicht durch den Agent-Proxy** (`ERR_CONNECTION_RESET`, auch mit
  `--proxy-server`). Kein JS-Rendering, also keine Bot-Challenges lösen.
- **Der Play-Endpoint `/r?t=<token>` ist von hier nicht auflösbar**: DDoS-Guard antwortet 403
  oder liefert eine „Checking your browser"-Seite. Die Redirect-Mechanik in `loadLinks` lässt
  sich hier also **nicht** verifizieren — nur auf dem Android TV des Users.

## Build- und Testzyklus

- **Direkt auf `main` committen und pushen.** CI baut nur `main`/`master`; Feature-Branches
  erzeugen keine `.cs3` und bringen in diesem Solo-Repo nichts.
- **Version in `<Provider>/build.gradle.kts` bei jeder Änderung hochzählen** — sonst bietet die
  App kein Update an.
- **Vor dem Push `./gradlew testDebugUnitTest` laufen lassen** — fängt Selektor-Brüche, die
  ein reiner Compile nicht sieht.
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

## Namenskonvention

Eigene Provider tragen ein **★-Suffix** im `MainAPI.name` und ein `★ Eigene Version —`-Präfix in
der `description` — der User nutzt parallel andere Extension-Repos mit gleichnamigen Providern.
Bei neuen Providern gleich so anlegen.

`MainAPI.name` ist der `apiName`, unter dem Lesezeichen und Watch-Fortschritt gespeichert werden.
**Nie ohne Rückfrage ändern** — eine Umbenennung entkoppelt vorhandene Nutzerdaten.

## loadLinks: `SourceCollector` benutzen, nicht selbst bauen

Diese vier Punkte sind die Ursache der meisten „Link-Fehler":

1. **Referer ist der Site-Root** (`"$mainUrl/"`), nicht die Episoden-URL. Hoster (voe, filemoon,
   vidmoly) liefern bei falschem Referer einen Anti-Bot-Stub statt Video.
2. **Redirect-Endpoints mit `allowRedirects = false`** aufrufen und den `location`-Header lesen.
   Die Kette durchlaufen zu lassen landet auf dem, was der Hoster einer refererlosen Anfrage gibt.
3. **`return sources.isNotEmpty()`** — nie unbedingtes `true`. Sonst meldet CloudStream Erfolg
   und zeigt nichts, was genau wie ein Ladefehler aussieht.
4. **Kein `runBlocking` im Extractor-Callback** (kann auf dem Main-Dispatcher hängen). Links erst
   sammeln, danach außerhalb mit `newExtractorLink` neu bauen.

**Sie stehen jetzt im Code:** `common/src/main/kotlin/com/germanstreams/common/SourceCollector.kt`
erzwingt alle vier. Ein neuer Provider sammelt mit `addRedirect(...)` / `addTarget(...)` und gibt
mit `emitTo(callback)` zurück — dann kann keine der Regeln mehr vergessen werden. Kein Provider
soll die Pipeline nochmal von Hand schreiben.

`SerienStreamProvider.kt` ist die kürzeste Vorlage.

## Gemeinsamer Code und Tests

- Geteilter Code liegt in `common/src/main/kotlin/` und wird per `sourceSets` in **jeden**
  Provider einkompiliert (kein Gradle-Modul — das erzeugte sonst eine Junk-`.cs3`).
- **Selektoren gehören in `common/.../parse/*Parser.kt` und dürfen keine CloudStream-Typen
  anfassen.** Nur so laufen sie als JVM-Unit-Tests. Sie liefern rohe hrefs; `fixUrl` macht der
  Provider.
- **Zu jedem Selektor gehört ein Test.** Fixture unter `<Provider>/src/test/resources/fixtures/`
  ablegen (getrimmt: Scripts, Styles, SVG-Pfade, `srcset` auf einen Kandidaten kürzen), Test
  daneben. `./gradlew testDebugUnitTest` läuft offline, `SMOKE=1 ./gradlew ... --tests '*LiveTest'`
  prüft gegen die echten Seiten.
- CI lässt die Fixture-Tests **vor** dem Build laufen; ein roter Test lässt die alten `.cs3`
  stehen, statt sie durch nichts zu ersetzen.

## Kotlin-Fallen, die hier schon zugeschlagen haben

- `apmap` ist deprecated-as-ERROR → **`amap`**.
- `ExtractorLink` hat kein `copy()`; neu bauen via `newExtractorLink(source, name, url, type) { … }`.
- `if (a) { x } else { y }.foo()` bindet `.foo()` an den else-Zweig → **klammern**.
- Weitere in DEVNOTES unter „CI gotchas".
