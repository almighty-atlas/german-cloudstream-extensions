# AniWorld HTML fixtures

## Provenance

Real HTTP 200 captures retrieved on 2026-10-16 UTC; not synthetic examples.
Python stdlib urllib with ordinary `User-Agent: Mozilla/5.0`; no authentication or bypass.
Redirect following was disabled; no assets, hosters, streams or redirect targets were requested.
Full response bodies were held in memory only; no complete pages or response headers were saved.
This compaction used existing fixtures only, without new network requests.

## Capture inventory

All URLs are on `https://aniworld.to`; timestamps below are UTC.
Original SHA256 covers complete response body bytes returned by urllib before decoding/trimming.
Fixture SHA256 covers the saved UTF-8 file, including its provenance comment and trailing newline.

| File | Source URL | Retrieved UTC | Original response SHA256 | Fixture SHA256 |
| --- | --- | --- | --- | --- |
| `aniworld-new-episodes-live.html` | https://aniworld.to/neue-episoden | `2026-10-16T11:43:42Z` | `b3b2c2cff8a6d6be0799bd4f87fba095ce450edebe7fcb0ac14c42106a3dd9f4` | `645fdf3c26923c101e3e325410b3a8fe6b14f6c18a036d6cfaad4c6e64a0f9e7` |
| `aniworld-one-piece-season-22-live.html` | https://aniworld.to/anime/stream/one-piece/staffel-22 | `2026-10-16T11:43:42Z` | `02f047aebc4ad00d7bbaad2f5f75ba08cfbe6875d4c34b6edb8e167f82579569` | `efffa5a56998519284af36d7f366a21f88851af15d681d4d4066c83a19be93f3` |
| `aniworld-one-piece-season-22-episode-34-live.html` | https://aniworld.to/anime/stream/one-piece/staffel-22/episode-34 | `2026-10-16T11:43:43Z` | `1608f4e4bee2eb0c331e64894f96791849ca7f37482079c545556f61c28bfc79` | `752a43310f1b66d6e7fd5006aad00fb18d9921a835131d7fc874baeddc85c504` |
| `aniworld-one-piece-season-22-episode-35-live.html` | https://aniworld.to/anime/stream/one-piece/staffel-22/episode-35 | `2026-10-16T11:43:43Z` | `77742ebf4131676c4f3a89d0665ab6ae8863f1c72c1309d081f1424bf8fe3a08` | `7d3aaf7142de81fd90677b3dd300598e476462b31cfc24b2fa2fa268c8ba8f54` |
| `aniworld-one-piece-season-22-episode-67-live.html` | https://aniworld.to/anime/stream/one-piece/staffel-22/episode-67 | `2026-10-16T11:43:43Z` | `f3545961bd7f4c1d4140534ce27d3e099729859415f5f5eb49e67820562898ca` | `314d297bfd04b2a8e37002c4d7048056f8dbdd90570e51d3b205d52e1d27c22e` |
| `aniworld-prince-of-tennis-films-live.html` | https://aniworld.to/anime/stream/the-prince-of-tennis/filme | `2026-10-16T11:43:43Z` | `22e0457a63517ef7365f82d370fa90e632dc315c0da2134cca282af533808e3c` | `3cd0eba196271220962eb9227fa32d647bb37a6aad42f1dbc624edd40ac34fc7` |
| `aniworld-prince-of-tennis-film-12-live.html` | https://aniworld.to/anime/stream/the-prince-of-tennis/filme/film-12 | `2026-10-16T11:43:43Z` | `5e3ce13ed507066eab67e2a58be6d28392b87839cf913245be6ad0ac3d479ac9` | `959fae09f3bc4c194f055e6ed6873ade60fd057fc159e2f192f5ce1cadacae9b` |

## Trimming

- Original source slices only: no DOM serialization, attribute rewriting or invented HTML.
- Each HTML comment records URL, original UTC retrieval time/hash and trimming details.
- Feed: eight selected rows, their outer `col-md-12` entries and real `newEpisodeList`/`rows` ancestors.
  Unselected outer entries are removed completely; no empty entry wrappers remain.
  JoJo S06 E05 appears in three languages; Link Click S04 E11 in two.
  Prince of Tennis films 11/12 and Bleach S17 E48 are also retained, in source order.
- One Piece season 22: original table, thead and tbody with only episode rows 34, 35 and 67.
- Film overview: original table structure with only Prince of Tennis films 1, 11 and 12.
- Hoster captures: original `hosterSiteVideo` wrapper, language picker and complete `ul.row` list.
  `data-lang-key`, `data-link-target`, link IDs, hrefs and flag markup remain unchanged.
- Scripts, CSS, player/iframe markup and unrelated page content are omitted.

## Observed flags

- Hoster keys: `1` = DE dub (`german.svg`), `2` = EN subtitles (`japanese-english.svg`),
  `3` = DE subtitles (`japanese-german.svg`).
- One Piece: episode 34 has keys 1/2/3; 35 only 3; 67 keys 2/3, with four hosters per key.
- Prince of Tennis film 12 has key 2 only, with four hosters; overview films have EN-sub flags.
- Feed lazy `data-src`, base64 placeholder `src` and `noscript` fallback images are original.
  Do not count fallback images as additional languages; preserve separate rows sharing one href.

## Limitations

- Selected HTML fragments, not full feeds/seasons/documents; gaps result from trimming.
- Flags and hoster lists are retrieval-time observations, not proof of playback or audio language.
- No redirect resolution, stream availability, login or anti-bot behavior was tested.
- Public link IDs remain; no response-header cookies or secrets are stored.
- Fixtures need no live network or environment flags. Synthetic fixtures remain byte-identical.
  Their historical comments (including the alleged static row legend) are not current site facts.
- Inline synthetic edge cases in the parser tests are not live captures.
