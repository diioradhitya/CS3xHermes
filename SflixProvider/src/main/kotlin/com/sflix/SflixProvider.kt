package com.sflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.sflix.model.*

class SflixProvider : MainAPI() {

    override var mainUrl = "https://ssflix.pro"
    override var name = "SFlix"
    override val hasMainPage = true
    override var lang = "id"

    // TMDB v3 API — public key used by ssflix.pro
    private val tmdbApiKey = "31eb6ae13f030d2e334cdd978cfc72b7"
    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbImage = "https://image.tmdb.org/t/p"

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "trending_movie_day"   to "Trending Movies",
        "now_playing"          to "Now Playing",
        "popular_movie"        to "Popular Movies",
        "top_rated_movie"      to "Top Rated Movies",
        "upcoming"             to "Upcoming Movies",
        "trending_tv_day"      to "Trending TV",
        "popular_tv"           to "Popular TV",
        "top_rated_tv"         to "Top Rated TV"
    )

    // Map section key → (TMDB endpoint path, isTvSeries)
    private data class Section(val path: String, val isTv: Boolean)

    private val sectionPaths = mapOf(
        "trending_movie_day"  to Section("/trending/movie/day",   false),
        "now_playing"         to Section("/movie/now_playing",    false),
        "popular_movie"       to Section("/movie/popular",         false),
        "top_rated_movie"     to Section("/movie/top_rated",       false),
        "upcoming"            to Section("/movie/upcoming",         false),
        "trending_tv_day"     to Section("/trending/tv/day",       true),
        "popular_tv"          to Section("/tv/popular",            true),
        "top_rated_tv"        to Section("/tv/top_rated",          true)
    )

    // ──────────────────────────────────────────────
    // getMainPage — TMDB list per section
    // ──────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sec = sectionPaths[request.data] ?: return newHomePageResponse(request.name, emptyList())
        val url = "$tmdbBase${sec.path}?api_key=$tmdbApiKey&language=en&page=$page"
        val items = fetchTmdbList(url, sec.isTv)
        return newHomePageResponse(request.name, items)
    }

    // ──────────────────────────────────────────────
    // search — multi-type: movies + TV
    // ──────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val movieResults = fetchTmdbList(
            "$tmdbBase/search/movie?api_key=$tmdbApiKey&language=en&query=$q&page=1&include_adult=false",
            isTv = false
        )
        val tvResults = fetchTmdbList(
            "$tmdbBase/search/tv?api_key=$tmdbApiKey&language=en&query=$q&page=1&include_adult=false",
            isTv = true
        )
        return (movieResults + tvResults).distinctBy { it.url }
    }

    // ──────────────────────────────────────────────
    // load — dispatch to movie or series handler
    // ──────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val (tmdbId, isTv) = parseUrl(url)
            ?: throw ErrorLoadingException("Invalid URL: $url")
        return if (isTv) loadSeries(tmdbId, url) else loadMovie(tmdbId, url)
    }

    // ──────────────────────────────────────────────
    // loadLinks — build iframe source URLs
    // Core fix: bypass evalJs(), construct direct iframe URLs
    // ──────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (tmdbId, isTv) = parseUrl(data) ?: return false

        // Extract season/episode from data string
        val season = Regex("""season=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episode = Regex("""episode=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        // 6 mirror hosts — each has a TMDB-ID-based embed URL
        val mirrors = listOf(
            "https://moviesapi.to/embed/movie/$tmdbId",
            "https://vidcore.net/embed/movie/$tmdbId",
            "https://videasy.net/embed/movie/$tmdbId",
            "https://vidfast.vc/embed/movie/$tmdbId",
            "https://vidsrc-embed.ru/e/$tmdbId",
            "https://embedmaster.link/embed/movie/$tmdbId"
        )

        // For TV: append season & episode to applicable mirrors
        val tvMirrors = if (isTv) {
            listOf(
                "https://vidsrc-embed.ru/e/$tmdbId?season=$season&episode=$episode",
                "https://moviesapi.to/embed/tv/$tmdbId/$season/$episode",
            )
        } else emptyList()

        val allMirrors = if (isTv) mirrors + tvMirrors else mirrors

        // Load via registered extractors — CloudStream auto-matches URLs
        allMirrors.forEach { mirrorUrl ->
            loadExtractor(mirrorUrl, mainUrl, subtitleCallback, callback)
        }

        return true
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    /** Parse /movie/{id} or /tv/{id} from internal URL. */
    private fun parseUrl(url: String): Pair<Int, Boolean>? {
        Regex("""/movie/(\d+)""").find(url)?.let {
            return it.groupValues[1].toIntOrNull()?.let { id -> id to false }
        }
        Regex("""/tv/(\d+)""").find(url)?.let {
            return it.groupValues[1].toIntOrNull()?.let { id -> id to true }
        }
        return null
    }

    /** Fetch TMDB list and map to SearchResponse. */
    private suspend fun fetchTmdbList(url: String, isTv: Boolean): List<SearchResponse> {
        val resp = app.get(url).parsedSafe<TmdbPaged>() ?: return emptyList()
        return resp.results.orEmpty().mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title ?: item.name ?: item.originalTitle
                ?: item.originalName ?: return@mapNotNull null
            val poster = item.posterPath?.let { "$tmdbImage/w500$it" }
            val rating = item.voteAverage?.let { Score.from10(it) }
            val year = (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull()
            val href = if (isTv) "$mainUrl/tv/$id" else "$mainUrl/movie/$id"

            if (isTv) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster; this.year = year; this.score = rating
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster; this.year = year; this.score = rating
                }
            }
        }
    }

    /** Build movie LoadResponse from TMDB detail. */
    private suspend fun loadMovie(tmdbId: Int, url: String): LoadResponse {
        val dataUrl = "$tmdbBase/movie/$tmdbId?api_key=$tmdbApiKey&language=en" +
                "&append_to_response=credits,videos,similar"
        val data = app.get(dataUrl).parsedSafe<TmdbMovieDetail>()
            ?: throw ErrorLoadingException("Movie not found")

        val title = data.title ?: data.originalTitle ?: "Unknown"
        val poster = data.posterPath?.let { "$tmdbImage/w500$it" }
        val backdrop = data.backdropPath?.let { "$tmdbImage/w1280$it" }
        val year = data.releaseDate?.take(4)?.toIntOrNull()
        val duration = data.runtime?.let { it / 60 }
        val rating = data.voteAverage?.let { Score.from10(it) }
        val tags = data.genres?.mapNotNull { it.name }.orEmpty()
        val actors = data.credits?.cast?.take(15)?.mapNotNull { it.name }.orEmpty()
        val trailer = data.videos?.results
            ?.filter { it.site == "YouTube" && (it.type == "Trailer" || it.type == "Teaser") }
            ?.maxByOrNull { it.official == true }
            ?.key

        val recommendations = data.similar?.results.orEmpty().mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val t = item.title ?: item.name ?: item.originalTitle ?: item.originalName
                ?: return@mapNotNull null
            val p = item.posterPath?.let { "$tmdbImage/w500$it" }
            newMovieSearchResponse(t, "$mainUrl/movie/$id", TvType.Movie) {
                this.posterUrl = p
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = data.overview
            this.duration = duration
            this.tags = tags
            this.score = rating
            addActors(actors)
            this.recommendations = recommendations
            addTrailer(trailer?.let { "https://www.youtube.com/watch?v=$it" })
        }
    }

    /** Build series LoadResponse with full season/episode list from TMDB. */
    private suspend fun loadSeries(tmdbId: Int, url: String): LoadResponse {
        val dataUrl = "$tmdbBase/tv/$tmdbId?api_key=$tmdbApiKey&language=en" +
                "&append_to_response=credits,videos,similar"
        val data = app.get(dataUrl).parsedSafe<TmdbTvDetail>()
            ?: throw ErrorLoadingException("Series not found")

        val title = data.name ?: data.originalName ?: "Unknown"
        val poster = data.posterPath?.let { "$tmdbImage/w500$it" }
        val backdrop = data.backdropPath?.let { "$tmdbImage/w1280$it" }
        val year = data.firstAirDate?.take(4)?.toIntOrNull()
        val rating = data.voteAverage?.let { Score.from10(it) }
        val tags = data.genres?.mapNotNull { it.name }.orEmpty()
        val actors = data.credits?.cast?.take(15)?.mapNotNull { it.name }.orEmpty()
        val trailer = data.videos?.results
            ?.filter { it.site == "YouTube" && (it.type == "Trailer" || it.type == "Teaser") }
            ?.maxByOrNull { it.official == true }
            ?.key

        val recommendations = data.similar?.results.orEmpty().mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val t = item.title ?: item.name ?: item.originalTitle ?: item.originalName
                ?: return@mapNotNull null
            val p = item.posterPath?.let { "$tmdbImage/w500$it" }
            newTvSeriesSearchResponse(t, "$mainUrl/tv/$id", TvType.TvSeries) {
                this.posterUrl = p
            }
        }

        val episodes = mutableListOf<Episode>()

        // Fetch each seasons episodes from TMDB

        // Fetch each season's episodes from TMDB
        val seasons = data.seasons?.filter { it.seasonNumber != 0 } ?: emptyList()
        seasons.forEach { season ->
            val seasonDetail = app.get(
                "$tmdbBase/tv/$tmdbId/season/${season.seasonNumber}?api_key=$tmdbApiKey&language=en"
            ).parsedSafe<TmdbSeasonDetail>()
            val seasonEps = seasonDetail?.episodes.orEmpty()
            val sip = season.posterPath?.let { "$tmdbImage/w500$it" }
            seasonEps.forEach { ep ->
                val epNum = ep.episodeNumber ?: 0
                val epName = ep.name ?: "Episode $epNum"
                val epPoster = ep.stillPath?.let { "$tmdbImage/w500$it" }
                    ?: sip
                val epUrl = "$mainUrl/tv/$tmdbId?season=${season.seasonNumber}&episode=$epNum"
                episodes.add(
                    newEpisode(epUrl) {
                        name = epName
                        episode = epNum
                        posterUrl = epPoster
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = data.overview
            this.tags = tags
            this.score = rating
            addActors(actors)
            this.recommendations = recommendations
            addTrailer(trailer?.let { "https://www.youtube.com/watch?v=$it" })
        }
    }
}
