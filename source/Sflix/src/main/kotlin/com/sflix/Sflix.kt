package com.sflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.StringUtils.encodeUrl
import java.net.URLEncoder

/**
 * SFlix CloudStream Plugin
 *
 * Strategy: this site is just a SPA that proxies TheMovieDB (TMDB) for metadata
 * and six public iframe hosts for video. We bypass ssflix.pro entirely and talk
 * to those sources directly — TMDB for everything (lists, search, detail, season,
 * episode metadata) and the six iframe hosts as video sources.
 *
 * Result: no Cloudflare challenge on ssflix.pro, no obfuscated JS to reverse,
 * no /geo.php to defeat. The plugin is small, fast, and stable.
 */
class Sflix : MainAPI() {
    override var mainUrl = "https://ssflix.pro"
    override var name = "SFlix"
    override val hasMainPage = true
    override var lang = "id"

    // TMDB v3 API key (publicly used by ssflix.pro — TMDB v3 is intentionally
    // shared with any client that wants to use it).
    private val tmdbApiKey = "31eb6ae13f030d2e334cdd978cfc72b7"
    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbImageBase = "https://image.tmdb.org/t/p"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "trending_movie_day" to "Trending Movies",
        "now_playing" to "Now Playing",
        "popular_movie" to "Popular Movies",
        "top_rated_movie" to "Top Rated Movies",
        "upcoming" to "Upcoming Movies",
        "trending_tv_day" to "Trending TV",
        "popular_tv" to "Popular TV",
        "top_rated_tv" to "Top Rated TV"
    )

    /** Map of section key -> (TMDB path, isTv). */
    private data class SectionInfo(val path: String, val isTv: Boolean)

    private val sectionPaths = mapOf(
        "trending_movie_day" to SectionInfo("/trending/movie/day", false),
        "now_playing" to SectionInfo("/movie/now_playing", false),
        "popular_movie" to SectionInfo("/movie/popular", false),
        "top_rated_movie" to SectionInfo("/movie/top_rated", false),
        "upcoming" to SectionInfo("/movie/upcoming", false),
        "trending_tv_day" to SectionInfo("/trending/tv/day", true),
        "popular_tv" to SectionInfo("/tv/popular", true),
        "top_rated_tv" to SectionInfo("/tv/top_rated", true)
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val info = sectionPaths[request.data] ?: return newHomePageResponse(request.name, emptyList())
        val url = "$tmdbBase${info.path}?api_key=$tmdbApiKey&language=en&page=$page"
        val items = fetchTmdbList(url, info.isTv)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val movieUrl = "$tmdbBase/search/movie?api_key=$tmdbApiKey&language=en&query=${query.encodeUrl()}&page=1&include_adult=false"
        val tvUrl = "$tmdbBase/search/tv?api_key=$tmdbApiKey&language=en&query=${query.encodeUrl()}&page=1&include_adult=false"
        val movieResults = fetchTmdbList(movieUrl, isTv = false)
        val tvResults = fetchTmdbList(tvUrl, isTv = true)
        return (movieResults + tvResults).distinctBy { it.url }
    }

    /** Parse any TMDB list response and convert to SearchResponse. */
    private suspend fun fetchTmdbList(url: String, isTv: Boolean): List<SearchResponse> {
        val response = app.get(url).parsedSafe<TmdbPaged>() ?: return emptyList()
        return response.results.orEmpty().mapNotNull { item ->
            val tmdbId = item.id ?: return@mapNotNull null
            val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName
                ?: return@mapNotNull null
            val poster = item.posterPath?.let { "$tmdbImageBase/w500$it" }
            val rating = item.voteAverage?.let { Score.from10(it) }
            val year = (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull()
            val href = if (isTv) "$mainUrl/tv/$tmdbId" else "$mainUrl/movie/$tmdbId"
            if (isTv) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = rating
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = rating
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val (tmdbId, isTv) = parseInternalUrl(url)
            ?: throw IllegalArgumentException("Bad URL: $url")

        return if (isTv) loadSeries(tmdbId, url) else loadMovie(tmdbId, url)
    }

    /** Parse our internal $mainUrl/movie/{id} or /tv/{id} URLs (or just /movie/123). */
    private fun parseInternalUrl(url: String): Pair<Int, Boolean>? {
        val movieMatch = Regex("/movie/(\\d+)").find(url)
        if (movieMatch != null) return movieMatch.groupValues[1].toIntOrNull()?.let { it to false }
        val tvMatch = Regex("/tv/(\\d+)").find(url)
        if (tvMatch != null) return tvMatch.groupValues[1].toIntOrNull()?.let { it to true }
        return null
    }

    private suspend fun loadMovie(tmdbId: Int, url: String): LoadResponse {
        val dataUrl = "$tmdbBase/movie/$tmdbId?api_key=$tmdbApiKey&language=en&append_to_response=credits"
        val data = app.get(dataUrl).parsedSafe<TmdbMovieDetail>()
            ?: throw ErrorLoadingException("Empty movie response")
        val title = data.title ?: data.originalTitle ?: "Unknown"
        val poster = data.posterPath?.let { "$tmdbImageBase/w500$it" }
        val backdrop = data.backdropPath?.let { "$tmdbImageBase/w1280$it" }
        val year = data.releaseDate?.take(4)?.toIntOrNull()
        val duration = data.runtime?.let { it / 60 }
        val rating = data.voteAverage?.let { Score.from10(it) }
        val tags = data.genres?.mapNotNull { it.name }.orEmpty()
        val actors = data.credits?.cast?.take(15)?.mapNotNull { it.name }.orEmpty()
        val recommendations = fetchTmdbList(
            "$tmdbBase/movie/$tmdbId/similar?api_key=$tmdbApiKey&language=en&page=1",
            isTv = false
        )

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
        }
    }

    private suspend fun loadSeries(tmdbId: Int, url: String): LoadResponse {
        val dataUrl = "$tmdbBase/tv/$tmdbId?api_key=$tmdbApiKey&language=en&append_to_response=credits"
        val data = app.get(dataUrl).parsedSafe<TmdbTvDetail>()
            ?: throw ErrorLoadingException("Empty TV response")
        val title = data.name ?: data.originalName ?: "Unknown"
        val poster = data.posterPath?.let { "$tmdbImageBase/w500$it" }
        val backdrop = data.backdropPath?.let { "$tmdbImageBase/w1280$it" }
        val year = data.firstAirDate?.take(4)?.toIntOrNull()
        val rating = data.voteAverage?.let { Score.from10(it) }
        val tags = data.genres?.mapNotNull { it.name }.orEmpty()
        val actors = data.credits?.cast?.take(15)?.mapNotNull { it.name }.orEmpty()
        val recommendations = fetchTmdbList(
            "$tmdbBase/tv/$tmdbId/similar?api_key=$tmdbApiKey&language=en&page=1",
            isTv = true
        )

        val seasons = data.seasons?.mapNotNull { season ->
            val seasonNumber = season.seasonNumber ?: return@mapNotNull null
            val seasonUrl = "$tmdbBase/tv/$tmdbId/season/$seasonNumber?api_key=$tmdbApiKey&language=en"
            val seasonData = app.get(seasonUrl).parsedSafe<TmdbSeasonDetail>()
                ?: return@mapNotNull null
            seasonData.episodes?.mapNotNull { ep ->
                val epNumber = ep.episodeNumber ?: return@mapNotNull null
                val epTitle = ep.name ?: "Episode $epNumber"
                val epPoster = ep.stillPath?.let { "$tmdbImageBase/w500$it" } ?: poster
                val epUrl = "$mainUrl/tv/$tmdbId?season=$seasonNumber&episode=$epNumber"
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.posterUrl = epPoster
                    this.description = ep.overview
                    this.score = ep.voteAverage?.let { Score.from10(it) }
                }
            }.orEmpty()
        }?.flatten().orEmpty()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, seasons) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = data.overview
            this.tags = tags
            this.score = rating
            addActors(actors)
            this.recommendations = recommendations
        }
    }

    /**
     * Returns all 6 streaming hosts as video links. Each is a simple iframe
     * wrapper around TMDB ID (and season/episode for TV). CloudStream's
     * built-in iframe parser will resolve these into actual .m3u8 streams.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (tmdbId, isTv) = parseInternalUrl(data) ?: return false

        val seasonEpisode = if (isTv) {
            val s = Regex("season=(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val e = Regex("episode=(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            "/$s/$e"
        } else ""

        val path = if (isTv) "tv/$tmdbId$seasonEpisode" else "movie/$tmdbId"

        val servers = listOf(
            "Server 1 — moviesapi.to" to "https://moviesapi.to/$path",
            "Server 2 — vidcore.net" to "https://vidcore.net/$path?autoPlay=true",
            "Server 3 — videasy.net" to "https://player.videasy.net/$path",
            "Server 4 — vidfast.vc" to "https://vidfast.vc/$path?autoPlay=true",
            "Server 5 — vidsrc-embed.ru" to "https://vidsrc-embed.ru/embed/$path",
            "Server 6 — embedmaster.link" to "https://embedmaster.link/$path"
        )

        servers.forEach { (name, url) ->
            // Try to resolve iframe via built-in extractors
            loadExtractor(url, "$mainUrl/", subtitleCallback) { link ->
                callback.invoke(link)
            }
            
            // Fallback: Provide direct link in case auto-resolve fails
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = url
                ) { this.referer = "$mainUrl/" }
            )
        }

        return true
    }

    // ---- TMDB response DTOs ----

    data class TmdbPaged(
        @JsonProperty("results") val results: List<TmdbItem>? = null
    )

    data class TmdbItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null
    )

    data class TmdbGenre(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class TmdbCastMember(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null
    )

    data class TmdbCredits(
        @JsonProperty("cast") val cast: List<TmdbCastMember>? = null
    )

    data class TmdbMovieDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null
    )

    data class TmdbSeason(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class TmdbTvDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
        @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null
    )

    data class TmdbEpisode(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null
    )

    data class TmdbSeasonDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
    )
}