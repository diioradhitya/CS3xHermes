package com.sflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * SFlix — Hybrid architecture:
 *   - TMDB API  → search, discover/popular, movie/tv details
 *   - moviesapi.to (Vidora API) → HLS stream URLs + subtitles
 */
class SflixProvider : MainAPI() {
    override var mainUrl = "https://moviesapi.to"
    override var name = "SFlix"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val TMDB_KEY = "e716f19ab4d25edc5247239a8f3494f8"
        private const val TMDB_BASE = "https://api.themoviedb.org/3"
        private const val IMG_BASE = "https://image.tmdb.org/t/p/"
        private const val VIDORA_BASE = "https://moviesapi.to/api/vidora/v1"
        private const val VIDORA_KEY = "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13"

        private fun posterUrl(path: String?): String? = path?.let { "${IMG_BASE}w500$it" }
        private fun backdropUrl(path: String?): String? = path?.let { "${IMG_BASE}w1280$it" }

        private fun tmdbUrl(endpoint: String, vararg extras: Pair<String, String>): String {
            val params = mutableListOf("api_key" to TMDB_KEY, "language" to "en-US")
            params.addAll(extras)
            val qs = params.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
            return "$TMDB_BASE$endpoint?$qs"
        }

        /** Extract genre names as a plain string list (for LoadResponse.tags). */
        private fun genreNames(genres: JSONArray): List<String> {
            val list = mutableListOf<String>()
            for (i in 0 until genres.length()) {
                val name = genres.optJSONObject(i)?.optString("name")?.ifBlank { null } ?: continue
                list.add(name)
            }
            return list
        }

        private fun mapQuality(source: String): Int {
            return when {
                source.contains("1080") -> Qualities.P1080.value
                source.contains("720")  -> Qualities.P720.value
                source.contains("480")  -> Qualities.P480.value
                source.contains("360")  -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
        }
    }

    // ============================================================
    // MAIN PAGES — TMDB discover
    // ============================================================

    override val mainPage = mainPageOf(
        "movie/popular" to "Popular Movies",
        "movie/top_rated" to "Top Rated Movies",
        "movie/upcoming" to "Upcoming Movies",
        "movie/now_playing" to "Now Playing",
        "tv/popular"      to "Popular TV",
        "tv/top_rated"    to "Top Rated TV",
        "tv/on_the_air"   to "On The Air",
        "tv/airing_today"  to "Airing Today"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isTv = request.data.startsWith("tv/")
        val url = tmdbUrl("/${request.data}", "page" to page.toString())
        val body = app.get(url).text
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return newHomePageResponse(request.name, emptyList())

        val items = (0 until results.length()).mapNotNull { i ->
            val obj = results.getJSONObject(i)
            val id = obj.optInt("id")
            val title = obj.optString("title").ifBlank { obj.optString("name") }
            if (title.isNullOrBlank()) return@mapNotNull null
            val poster = posterUrl(obj.optString("poster_path", null))
            val release = obj.optString("release_date").ifBlank { obj.optString("first_air_date") }
            val type = if (isTv) "tv" else "movie"
            val fakeUrl = "tmdb://$type/$id"

            if (isTv) {
                newTvSeriesSearchResponse(title, fakeUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = release.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()
                }
            } else {
                newMovieSearchResponse(title, fakeUrl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = release.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()
                }
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = false))
        )
    }

    // ============================================================
    // SEARCH — TMDB multi-search
    // ============================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = tmdbUrl("/search/multi", "query" to query, "page" to "1")
        val body = app.get(url).text
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return emptyList()

        return (0 until results.length()).mapNotNull { i ->
            val obj = results.getJSONObject(i)
            val mediaType = obj.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null

            val id = obj.optInt("id")
            val title = obj.optString("title").ifBlank { obj.optString("name") }
            if (title.isNullOrBlank()) return@mapNotNull null

            val poster = posterUrl(obj.optString("poster_path", null))
            val release = obj.optString("release_date").ifBlank { obj.optString("first_air_date") }
            val fakeUrl = "tmdb://$mediaType/$id"

            if (mediaType == "tv") {
                newTvSeriesSearchResponse(title, fakeUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = release.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()
                }
            } else {
                newMovieSearchResponse(title, fakeUrl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = release.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()
                }
            }
        }
    }

    // ============================================================
    // LOAD — TMDB movie/tv detail + episodes
    // ============================================================

    override suspend fun load(url: String): LoadResponse {
        val match = Regex("""tmdb://(movie|tv)/(\d+)""").find(url)
            ?: return newMovieLoadResponse("Error", url, TvType.Movie, url)

        val type = match.groupValues[1]
        val tmdbId = match.groupValues[2].toInt()
        val isTv = type == "tv"
        val body = app.get(tmdbUrl("/$type/$tmdbId")).text
        val json = JSONObject(body)

        val title = json.optString("title").ifBlank { json.optString("name") }
        val plot = json.optString("overview")
        val poster = posterUrl(json.optString("poster_path", null))
        val backdrop = backdropUrl(json.optString("backdrop_path", null))
        val year = json.optString("release_date").ifBlank { json.optString("first_air_date") }
            .takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()
        val tags = genreNames(json.optJSONArray("genres") ?: JSONArray())

        return if (isTv) {
            val seasons = json.optJSONArray("seasons") ?: JSONArray()
            val episodes = mutableListOf<Episode>()

            for (s in 0 until seasons.length()) {
                val seasonObj = seasons.getJSONObject(s)
                val seasonNum = seasonObj.optInt("season_number")
                if (seasonNum < 1) continue

                val seasonBody = app.get(tmdbUrl("/tv/$tmdbId/season/$seasonNum")).text
                val seasonJson = JSONObject(seasonBody)
                val epArray = seasonJson.optJSONArray("episodes") ?: continue

                for (e in 0 until epArray.length()) {
                    val epObj = epArray.getJSONObject(e)
                    val epNum = epObj.optInt("episode_number")
                    val epTitle = epObj.optString("name")
                    val epOverview = epObj.optString("overview")
                    val epStill = epObj.optString("still_path", null)
                    val epPoster = posterUrl(epStill)

                    val epUrl = "tmdb://tv/$tmdbId?s=$seasonNum&e=$epNum"
                    episodes.add(newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                        this.description = epOverview
                        this.posterUrl = epPoster
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            val movieUrl = "tmdb://movie/$tmdbId"
            newMovieLoadResponse(title, url, TvType.Movie, movieUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    // ============================================================
    // LOAD LINKS — Vidora API (moviesapi.to)
    // ============================================================

    override suspend fun loadLinks(
        url: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = Regex("""tmdb://(movie|tv)/(\d+)(?:\?s=(\d+)&e=(\d+))?""").find(url)
            ?: return false

        val type = match.groupValues[1]
        val tmdbId = match.groupValues[2]
        val season = match.groupValues.getOrNull(3)
        val episode = match.groupValues.getOrNull(4)
        val isTv = type == "tv"

        val vidoraUrl = if (isTv && season != null && episode != null && season.isNotBlank() && episode.isNotBlank()) {
            "$VIDORA_BASE/tv/$tmdbId/$season/$episode"
        } else if (isTv) {
            "$VIDORA_BASE/tv/$tmdbId/1/1"
        } else {
            "$VIDORA_BASE/movie/$tmdbId"
        }

        val headers = mapOf(
            "x-player-key" to VIDORA_KEY,
            "Referer" to "https://moviesapi.to/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        )

        val response = app.get(vidoraUrl, headers = headers).text
        // Strip any HTML wrapper; parse the JSON portion
        val json = try {
            JSONObject(response)
        } catch (e: Exception) {
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}")
            if (jsonStart < 0 || jsonEnd <= jsonStart) return false
            JSONObject(response.substring(jsonStart, jsonEnd + 1))
        }

        val sources = json.optJSONArray("sources") ?: return false
        var found = false

        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val videoUrl = source.optString("url")
            if (videoUrl.isBlank() || !videoUrl.contains(".m3u8")) continue

            val sourceLabel = source.optString("quality", "HLS")
            val quality = mapQuality(sourceLabel)

            callback.invoke(
                newExtractorLink("SFlix", sourceLabel, videoUrl) {
                    this.quality = quality
                    this.headers = headers
                }
            )
            found = true
        }

        // Subtitles
        val tracks = json.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until tracks.length()) {
            val track = tracks.getJSONObject(i)
            val trackUrl = track.optString("file")
            if (trackUrl.isBlank()) continue

            val trackLabel = track.optString("label", "Unknown")
            val trackKind = track.optString("kind", "")
            if (trackKind == "captions" || trackKind == "subtitles" || trackUrl.contains(".srt") || trackUrl.contains(".vtt")) {
                subtitleCallback.invoke(SubtitleFile(trackLabel, trackUrl))
            }
        }

        return found
    }
}
