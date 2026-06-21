# German CloudStream Extensions

CloudStream-Plugins (`.cs3`) für deutschsprachige Streaming-Anbieter. Basiert auf dem offiziellen
[TestPlugins](https://github.com/recloudstream/TestPlugins)-Template.

## Status der Provider

| Provider | Ordner | Typ | Status |
|----------|--------|-----|--------|
| AniWorld (`aniworld.to`) | `AniWorld/` | Anime (Sub & Dub) | ✅ gebaut, Selektoren live verifiziert |
| s.to (SerienStream) | – | Serien | ⏳ geplant (gleiche Familie wie AniWorld) |
| bs.to (BurningSeries) | – | Serien/Anime | ⏳ geplant |
| anime-loads.org | – | Anime | ⏳ geplant |
| kinox / movie4k / movie2k / megakino | – | Filme | ⏳ geplant |
| moflix, kinoger, filmpalast, chillflix, cineby, kinoking, kinos, aether, streamcloud, streamkiste, einschalten, haschcon | – | Filme/Serien | ⏳ geplant |

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

## Lokaler Aufbau eines Providers

- `Provider/build.gradle.kts` – Metadaten (`cloudstream { ... }`), Version, `namespace`.
- `Provider/src/main/AndroidManifest.xml` – leeres `<manifest />`.
- `…/XxxPlugin.kt` – `@CloudstreamPlugin`, registriert via `registerMainAPI(...)`.
- `…/XxxProvider.kt` – `MainAPI`-Subklasse mit 4 Kernmethoden:
  - `getMainPage` – Startseiten-Listen
  - `search` – Suche
  - `load` – Detailseite → Episoden/Metadaten
  - `loadLinks` – Stream-Quellen (meist `loadExtractor` auf Hoster-Embeds)
