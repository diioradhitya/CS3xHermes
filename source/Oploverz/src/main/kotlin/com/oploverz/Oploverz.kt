package com.oploverz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.StringUtils.encodeUrl

// =====================================================================
// OPLOVERZ - Anime Sub Indo streaming plugin
// API: https://backapi.oploverz.ac/api/{series,episodes,genres}
// Site: https://oploverz.site
// =====================================================================

class Oploverz : MainAPI() {
    override var mainUrl = "https://backapi.oploverz.ac"
    override var name = "Oploverz"
    override val supportedTypes = setOf(TvType.Anime, TvType.AsianDrama, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override var lang = "id"

    override val mainPage = mainPageOf(
        "page" to "Anime Terbaru",
        "ongoing" to "Ongoing",
        "completed" to "Completed",
        "movie" to "Movie"
    )

    private var cachedGenres: List<Pair<String, String>>? = null

    // ===== API DTOs =====

    data class Meta(
        @JsonProperty("currentPage") val currentPage: Int = 1,
        @JsonProperty("lastPage") val lastPage: Int = 1,
        @JsonProperty("total") val total: Int = 0
    )

    data class Genre(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("name") val name: String = "",
        @JsonProperty("slug") val slug: String = ""
    )

    data class GenreList(
        @JsonProperty("meta") val meta: Meta = Meta(),
        @JsonProperty("data") val data: List<Genre> = emptyList()
    )

    data class SeriesItem(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("seriesId") val seriesId: Int = 0,
        @JsonProperty("title") val title: String = "",
        @JsonProperty("japaneseTitle") val japaneseTitle: String? = null,
        @JsonProperty("slug") val slug: String = "",
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("status") val status: String = "",
        @JsonProperty("poster") val poster: String = "",
        @JsonProperty("duration") val duration: String? = null,
        @JsonProperty("releaseType") val releaseType: String = "",
        @JsonProperty("score") val score: Double? = null,
        @JsonProperty("genres") val genres: List<Genre> = emptyList()
    )

    data class SeriesList(
        @JsonProperty("meta") val meta: Meta = Meta(),
        @JsonProperty("data") val data: List<SeriesItem> = emptyList()
    )

    data class StreamSource(
        @JsonProperty("source") val source: String = "",
        @JsonProperty("url") val url: String = ""
    )

    data class Episode(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("subbed") val subbed: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: String = "",
        @JsonProperty("streamUrl") val streamUrl: List<StreamSource>? = null,
        @JsonProperty("releasedAt") val releasedAt: String? = null,
        @JsonProperty("series") val series: SeriesItem? = null
    )

    data class EpisodeList(
        @JsonProperty("meta") val meta: Meta = Meta(),
        @JsonProperty("data") val data: List<Episode> = emptyList()
    )

    data class EpisodeSingle(
        @JsonProperty("data") val data: Episode = Episode()
    )

    // ===== Helpers =====

    private suspend fun fetchSeries(page: Int): SeriesList {
        return app.get("$mainUrl/api/series?page=$page").parsedSafe<SeriesList>() ?: SeriesList()
    }

    private suspend fun searchSeries(query: String, page: Int = 1): SeriesList {
        return app.get("$mainUrl/api/series?q=${query.encodeUrl()}&page=$page")
            .parsedSafe<SeriesList>() ?: SeriesList()
    }

    private suspend fun fetchEpisodes(seriesId: Int, page: Int = 1): EpisodeList {
        return app.get("$mainUrl/api/episodes?seriesId=$seriesId&page=$page")
            .parsedSafe<EpisodeList>() ?: EpisodeList()
    }

    private suspend fun fetchGenres(): List<Pair<String, String>> {
        cachedGenres?.let { return it }
        val list = app.get("$mainUrl/api/genres").parsedSafe<GenreList>()
            ?.data?.map { it.name to it.slug }.orEmpty()
        cachedGenres = list
        return list
    }

    private fun posterUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "$mainUrl/$path"
    }

    private fun parseDurationToMinutes(duration: String?): Int? {
        if (duration.isNullOrBlank()) return null
        return try {
            val minMatch = Regex("(\\d+)\\s*min").find(duration)
            val hourMatch = Regex("(\\d+)\\s*jam").find(duration)
            val minPart = Regex("(\\d+)\\s*menit").find(duration)
            val totalMin = (hourMatch?.groupValues?.getOrNull(1)?.toInt()?.times(60) ?: 0) +
                (minMatch?.groupValues?.getOrNull(1)?.toInt() ?: 0) +
                (minPart?.groupValues?.getOrNull(1)?.toInt() ?: 0)
            if (totalMin > 0) totalMin else null
        } catch (e: Exception) { null }
    }

    // ===== Main page =====

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = fetchSeries(page).data
        val filtered = when (request.data) {
            "ongoing" -> all.filter { it.status.equals("Ongoing", true) }
            "completed" -> all.filter { it.status.equals("Completed", true) }
            "movie" -> all.filter { it.releaseType.equals("Movie", true) }
            else -> all
        }
        return newHomePageResponse(request.name, filtered.map { it.toSearchResponse() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            searchSeries(query).data.map { it.toSearchResponse() }
        } catch (e: Exception) { emptyList() }
    }

    // ===== Load detail =====

    override suspend fun load(url: String): LoadResponse? {
        // url is full API URL like "https://backapi.oploverz.ac/api/series/1734"
        // Extract seriesId from the path
        val seriesIdMatch = Regex("/api/series/(\\d+)").find(url)
        val seriesId = seriesIdMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null

        // Fetch all series on page 1, 2, 3 to find the matching id
        var series: SeriesItem? = null
        for (p in 1..5) {
            val list = fetchSeries(p).data
            series = list.firstOrNull { it.id == seriesId }
            if (series != null) break
        }
        return series?.let { loadSeriesDetail(it) }
    }

    private suspend fun loadSeriesDetail(series: SeriesItem): LoadResponse {
        val episodes = fetchEpisodes(series.id).data.filter { it.series?.id == series.id }
        val epList = episodes.mapNotNull { ep ->
            val epNum = ep.episodeNumber.toIntOrNull() ?: return@mapNotNull null
            // Episode URL: use full API endpoint so loadLinks can fetch it
            // directly. CloudStream persists data as opaque string.
            val epUrl = "$mainUrl/api/episodes/${ep.id}"
            newEpisode(epUrl) {
                this.name = ep.title ?: "Episode ${ep.episodeNumber}"
                this.episode = epNum
                this.posterUrl = posterUrl(series.poster)
                this.description = ep.releasedAt?.let { "Released: ${it.take(10)}" }
            }
        }.sortedBy { it.episode }

        val isMovie = series.releaseType.equals("Movie", true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        return if (isMovie) {
            newMovieLoadResponse(series.title, "${mainUrl}/api/series/${series.id}", type, "${mainUrl}/api/episodes/${episodes.firstOrNull()?.id ?: 0}") {
                this.posterUrl = posterUrl(series.poster)
                this.backgroundPosterUrl = posterUrl(series.poster)
                this.year = series.releaseDate?.take(4)?.toIntOrNull()
                this.plot = series.description
                this.duration = parseDurationToMinutes(series.duration)
                this.score = series.score?.let { Score.from10(it) }
                this.tags = series.genres.map { it.name }
            }
        } else {
            newTvSeriesLoadResponse(series.title, "${mainUrl}/api/series/${series.id}", type, epList) {
                this.posterUrl = posterUrl(series.poster)
                this.backgroundPosterUrl = posterUrl(series.poster)
                this.year = series.releaseDate?.take(4)?.toIntOrNull()
                this.plot = series.description
                this.duration = parseDurationToMinutes(series.duration)
                this.score = series.score?.let { Score.from10(it) }
                this.tags = series.genres.map { it.name }
            }
        }
    }

    // ===== Load links =====

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is full URL like "https://backapi.oploverz.ac/api/episodes/45538"
        val resp = app.get(data).parsedSafe<EpisodeSingle>()
            ?: return false

        val sources = resp.data.streamUrl ?: return false
        if (sources.isEmpty()) return false

        var found = false
        sources.forEach { src ->
            // Add direct stream URL as primary link (CloudStream will try
            // to play it directly, e.g. via WebView which solves the
            // Cloudflare challenge on-device).
            callback(
                newExtractorLink(
                    source = name,
                    name = src.source,
                    url = src.url
                ) { this.referer = "${mainUrl}/" }
            )
            found = true

            // Also try the StreamWish-based extractor (UpBolt) as a
            // secondary attempt — this resolves upbolt.to's iframe into
            // an m3u8 stream when Cloudflare passes.
            try {
                loadExtractor(
                    url = src.url,
                    referer = "${mainUrl}/",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            } catch (e: Exception) {
                // extractor failed silently; direct link already added above
            }
        }
        return found
    }

    // ===== Mappers =====

    private fun SeriesItem.toSearchResponse(): SearchResponse {
        val isMovie = releaseType.equals("Movie", true)
        val type = if (isMovie) TvType.Movie else TvType.Anime
        // Use full API URL as the canonical identifier so load() can
        // resolve it directly without round-tripping through search.
        val dataUrl = "$mainUrl/api/series/$id"
        return newAnimeSearchResponse(title, dataUrl, type) {
            this.posterUrl = posterUrl(this@toSearchResponse.poster)
            this.year = releaseDate?.take(4)?.toIntOrNull()
            this.score = this@toSearchResponse.score?.let { Score.from10(it) }
        }
    }
}