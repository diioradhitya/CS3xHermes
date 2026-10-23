# Changelog

## v2026.10.21.1 (Loklok added)
- Added **Loklok** plugin: drama, film & anime subtitle Indonesia from `loklok.my.id`
  - 4 platform: DrakorID (Kdrama), FilmBox (Movie/Series), AnimeKompi (Anime), iQIYI
  - Home page mirroring the site's own sections + 6 genre tabs
  - Subtitle multi-bahasa (Indonesia, English, Malay, Mandarin, dll)
  - HLS (drakorid, iQIYI) dan MP4 (filmbox, animekompi)
  - Endpoint traced from the site's JS bundle, not guessed
  - Detail of the work: `docs/ACTIVITY.md`

## v2026.08.30.2 (Kawanfilm added)
- Added **Kawanfilm** plugin: Movie, TV Series, Anime, AsianDrama from `web.kawanfilm21.co`
  - Subtitle Indonesia built-in
  - Multiple server tabs (p1-p4) loaded via AJAX
  - Supports: Vidshare, StreamWish, Morencius, Earnvids extractors
- SFlix kept as-is (work in progress, player needs WebView/browser mode)

## v2026.08.30.1 (Initial release)
- First build of SFlix CloudStream plugin
- Strategy: TMDB API for metadata + 6 iframe hosts for video
- Supports: Movie & TV Series
- Language: Indonesian (id)
- 6 video sources: moviesapi.to, vidcore.net, videasy.net, vidfast.vc, vidsrc-embed.ru, embedmaster.link