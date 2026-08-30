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
        // url is in format "slug|seriesId" or just "slug"
        val parts = url.split("|", limit = 2)
        val slug = parts[0]
        val seriesId = parts.getOrNull(1)?.toIntOrNull()

        // We have two paths:
        // 1) If we already know seriesId (from toSearchResponse), use it directly
        // 2) Otherwise look up by slug via search
        val series = if (seriesId != null && seriesId > 0) {
            fetchSeries(1).data.firstOrNull { it.id == seriesId }
        } else {
            searchSeries(slug).data.firstOrNull { it.slug == slug }
        } ?: return null

        return loadSeriesDetail(series)
    }

    private suspend fun loadSeriesDetail(series: SeriesItem): LoadResponse {
        val episodes = fetchEpisodes(series.id).data
        val epList = episodes.mapNotNull { ep ->
            val epNum = ep.episodeNumber.toIntOrNull() ?: return@mapNotNull null
            val epUrl = "${series.slug}|${ep.id}|${epNum}"
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
            newMovieLoadResponse(series.title, "${series.slug}|${series.id}", type, "${series.slug}|${series.id}") {
                this.posterUrl = posterUrl(series.poster)
                this.backgroundPosterUrl = posterUrl(series.poster)
                this.year = series.releaseDate?.take(4)?.toIntOrNull()
                this.plot = series.description
                this.duration = parseDurationToMinutes(series.duration)
                this.score = series.score?.let { Score.from10(it) }
                this.tags = series.genres.map { it.name }
            }
        } else {
            newTvSeriesLoadResponse(series.title, "${series.slug}|${series.id}", type, epList) {
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
        // data is "slug|episodeId|episodeNum"
        val parts = data.split("|", limit = 3)
        if (parts.size < 2) return false
        val episodeId = parts[1].toIntOrNull() ?: return false

        val resp = app.get("$mainUrl/api/episodes/$episodeId").parsedSafe<EpisodeSingle>()
            ?: return false

        val sources = resp.data.streamUrl ?: return false
        if (sources.isEmpty()) return false

        sources.forEach { src ->
            val quality = when {
                src.source.contains("1080", true) -> Qualities.P1080
                src.source.contains("720", true) -> Qualities.P720
                src.source.contains("480", true) -> Qualities.P480
                src.source.contains("360", true) -> Qualities.P360
                else -> Qualities.Unknown
            }
            val qualityValue = quality.value
            callback(
                newExtractorLink(
                    source = "Oploverz",
                    name = src.source,
                    url = src.url
                ) {
                    this.referer = "${mainUrl}/"
                    this.quality = qualityValue
                }
            )
            // Try StreamWish-based fallback extractor (UpBolt) if Cloudflare passes
            try {
                loadExtractor(src.url, subtitleCallback, callback)
            } catch (e: Exception) {
                // ignore — direct link already added
            }
        }
        return true
    }

    // ===== Mappers =====

    private fun SeriesItem.toSearchResponse(): SearchResponse {
        val isMovie = releaseType.equals("Movie", true)
        val type = if (isMovie) TvType.Movie else TvType.Anime
        return newAnimeSearchResponse(title, "$slug|$id", type) {
            this.posterUrl = posterUrl(this@toSearchResponse.poster)
            this.year = releaseDate?.take(4)?.toIntOrNull()
            this.score = this@toSearchResponse.score?.let { Score.from10(it) }
        }
    }
}