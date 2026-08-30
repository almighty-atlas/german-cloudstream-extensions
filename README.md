# German CloudStream Extensions

CloudStream-Plugins (`.cs3`) für deutschsprachige Streaming-Anbieter. Basiert auf dem offiziellen
[TestPlugins](https://github.com/recloudstream/TestPlugins)-Template.

## Status der Provider

| Provider | Ordner | Typ | Status |
|----------|--------|-----|--------|
| AniWorld (`aniworld.to`) | `AniWorld/` | Anime (Sub & Dub) | ✅ v14, auf TV getestet |
| SerienStream (`serienstream.to` / `s.to`) | `SerienStream/` | Serien | 🧪 v6, auf TV noch ungetestet |
| Filmo (`filmo.to`) | `Filmo/` | Filme | 🧪 v4, auf TV noch ungetestet |
| bs.to (BurningSeries) | – | Serien/Anime | ⏳ geplant |
| anime-loads.org | – | Anime | ⏳ geplant |
| kinox / movie4k / movie2k / megakino | – | Filme | ⏳ geplant |
| moflix, kinoger, filmpalast, chillflix, cineby, kinoking, kinos, aether, streamcloud, streamkiste, einschalten, haschcon | – | Filme/Serien | ⏳ geplant |

Entwickler-Details + offene TODOs: siehe [`DEVNOTES.md`](DEVNOTES.md).

## Setup (einmalig)

1. GitHub-Repo anlegen, diesen Ordner pushen (Branch `main` oder `master`).
2. Leeren **`builds`**-Branch anlegen (der Workflow checkt ihn aus):
   ```bash
   git checkout --orphan builds && git rm -rf . && git commit --allow-empty -m "init builds" && git push origin builds
   git checkout main
   ```
3. In GitHub: **Settings → Actions → General** → "Allow all actions" + "Read and write permissions".
4. Push auf `main` → Workflow baut alle `.cs3` + `plugins.json` auf den `builds`-Branch.

## In CloudStream installieren (Android TV)

Einstellungen → Erweiterungen → Repository hinzufügen → URL:

```
https://raw.githubusercontent.com/<USER>/<REPO>/builds/plugins.json
```

Danach in der Repo-Liste die einzelnen Provider installieren.

## Aufbau des Repos

```
common/src/main/kotlin/    von allen Providern geteilter Code (kein Gradle-Modul, s. u.)
  ├─ Net.kt                HTTP-Defaults, Redirect-Auflösung
  ├─ SourceLanguage.kt     Dub / Ger-Sub / Eng-Sub
  ├─ SourceCollector.kt    die gesamte loadLinks-Pipeline
  └─ parse/                Selektoren, frei von CloudStream-Typen → JVM-testbar
<Provider>/src/main/       der eigentliche Provider (dünn: mappt Parser-Ergebnisse)
<Provider>/src/test/       Tests + Seiten-Fixtures
```

Der gemeinsame Code liegt bewusst in einem Source-Ordner statt in einem eigenen Gradle-Modul:
jede `.cs3` ist ein eigenständiges Dex, der Code muss also ohnehin in jedes Plugin — ein Modul
würde zusätzlich eine leere `.cs3` erzeugen.

## Tests

```bash
./gradlew testDebugUnitTest                                   # offline, gegen Fixtures
SMOKE=1 ./gradlew testDebugUnitTest --tests '*LiveTest'       # gegen die echten Seiten
```

Ein grüner Build heißt nur „kompiliert". Die Fixture-Tests prüfen, ob die Selektoren noch
greifen — sie laufen in CI vor dem Build, und ein nächtlicher Workflow
(`selector-smoke.yml`) prüft dasselbe gegen die Live-Seiten und meldet einen Site-Umbau als
Issue. Ob Streams tatsächlich abspielen, testet weiterhin nur der Fernseher.

## Wenn eine Site umzieht

Alle Provider erlauben einen URL-Override: in CloudStream unter den Provider-Einstellungen die
neue Domain eintragen — ohne auf einen neuen Build zu warten.

## Lokaler Aufbau eines Providers

- `Provider/build.gradle.kts` – Metadaten (`cloudstream { ... }`), Version, `namespace`.
- `Provider/src/main/AndroidManifest.xml` – leeres `<manifest />`.
- `…/XxxPlugin.kt` – `@CloudstreamPlugin`, registriert via `registerMainAPI(...)`.
- `…/XxxProvider.kt` – `MainAPI`-Subklasse mit 4 Kernmethoden:
  - `getMainPage` – Startseiten-Listen
  - `search` – Suche
  - `load` – Detailseite → Episoden/Metadaten
  - `loadLinks` – Stream-Quellen (meist `loadExtractor` auf Hoster-Embeds)
