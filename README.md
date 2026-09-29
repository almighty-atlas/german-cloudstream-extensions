# German CloudStream Extensions

CloudStream-Plugins (`.cs3`) für deutschsprachige Streaming-Anbieter. Basiert auf dem offiziellen
[TestPlugins](https://github.com/recloudstream/TestPlugins)-Template.

## Status der Provider

| Provider | Ordner | Typ | Status |
|----------|--------|-----|--------|
| AniWorld (`aniworld.to`) | `AniWorld/` | Anime (Sub & Dub) | 🧪 v15, Anzeige-Korrektur noch nicht auf TV getestet |
| SerienStream (`serienstream.to` / `s.to`) | `SerienStream/` | Serien | 🧪 v6, auf TV noch ungetestet |
| Filmo (`filmo.to`) | `Filmo/` | Filme | 🧪 v5, auf TV noch ungetestet |
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
https://raw.githubusercontent.com/almighty-atlas/german-cloudstream-extensions/main/repo.json
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

## Tests und lokaler Build

```bash
./gradlew -p smoke :AniWorld:test :Filmo:test :SerienStream:test
SMOKE=1 ./gradlew -p smoke :test --tests '*LiveTest' --rerun-tasks

python3 scripts/prepare_cloudstream_gradle.py
./gradlew make makePluginsJson
```

Die Fixture-Tests laufen offline und unabhängig vom Android-/CloudStream-Plugin. Der tägliche
Smoke-Test prüft neun Live-Fälle für SerienStream und Filmo; AniWorld blockiert CI-Anfragen.
Die Ergebnisse und Logs werden bei Fehlschlägen als Workflow-Artefakt gespeichert.

Für den Plugin-Build holt das Vorbereitungsskript den festgelegten Upstream-Stand
`cce1b8d84dc796b8da4a92a64cedfb046d1937b3` nach `cloudstream-gradle/` und entfernt
die hier nicht benötigte ADB-Deploy-Aufgabe samt JitPack-Abhängigkeit. Der Ordner ist
generiert und wird nicht committed. Der CI-Workflow checkt denselben Commit aus.
Ob Streams tatsächlich abspielen, testet weiterhin nur der Fernseher.

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
