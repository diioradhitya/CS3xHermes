package com.sflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import org.jsoup.nodes.Element

class SflixProvider : MainAPI() {
    override var mainUrl = "https://insflix.biz"
    override var name = "SFlix"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "popular-movies" to "Popular Movies",
        "top-rated-movies" to "Top Rated Movies",
        "upcoming-movies" to "Upcoming Movies",
        "featured-movies" to "Featured Movies",
        "popular-tv-shows" to "Popular TV",
        "top-rated-tv-shows" to "Top Rated TV",
        "airing-today-tv-shows" to "Airing Today"
    )

    /**
     * insflix.biz lazy-loads all images:
     *   src="...loading.gif"  (placeholder — never blank)
     *   data-src="https://...actual-poster.jpg"
     * Must use data-src first, skip loading.gif.
     */
    private fun Element.getImage(): String? {
        val dataSrc = this.select("img").attr("data-src").ifBlank { null }
        if (dataSrc != null && !dataSrc.contains("loading.gif")) return dataSrc
        val src = this.select("img").attr("src").ifBlank { null }
        if (src != null && !src.contains("loading.gif")) return src
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isTv = request.data.contains("tv")
        val url = "$mainUrl/${request.data}/page/$page/"
        val doc = app.get(url).document

        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { li ->
            val a = li.select("a").firstOrNull() ?: return@mapNotNull null
            // Title is in <h2 class="Title">
            val title = li.select("h2.Title").text()
                .ifBlank { li.select("h2").text() }
                .ifBlank { a.attr("title") }
                .ifBlank { li.select("div.Title").text() }
                .ifBlank { return@mapNotNull null }
            val href = a.attr("href")
            val poster = li.getImage()
            if (isTv) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    items,
                    isHorizontalImages = false
                )
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val doc = app.get(url).document

        val results = mutableListOf<SearchResponse>()
        doc.select("ul.MovieList li.TPostMv").forEach { li ->
            val a = li.select("a").firstOrNull() ?: return@forEach
            val title = li.select("h2.Title").text()
                .ifBlank { li.select("h2").text() }
                .ifBlank { a.attr("title") }
                .ifBlank { li.select("div.Title").text() }
                .ifBlank { return@forEach }
            val href = a.attr("href")
            val poster = li.getImage()
            val isTv = href.contains("/shows/")
            if (isTv) {
                results.add(
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            } else {
                results.add(
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // Title: <h1 class="Title"> on insflix.biz
        val title = doc.select("h1.Title").text()
            .ifBlank { doc.select("h2.Title").text() }
            .ifBlank { doc.select("h1").text() }

        // Description: <div class="Description"><p>...</p></div>
        val description = doc.select("div.Description p").text()
            .ifBlank { doc.select("div.entry-content p").text() }

        // Poster: look for TMDB CDN image via data-src (skip loading.gif)
        val poster = doc.select("img[data-src*=themoviedb]").attr("data-src").ifBlank { null }
            ?: doc.select("img[data-src*=tmdb]").attr("data-src").ifBlank { null }
            ?: doc.select("img[src*=themoviedb]").attr("src").ifBlank { null }
            // fallback: first non-loading img
            ?: run {
                var found: String? = null
                doc.select("img").forEach { img ->
                    val ds = img.attr("data-src").ifBlank { img.attr("src") }
                    if (ds.isNotBlank() && !ds.contains("loading.gif") && !ds.contains("logo")) {
                        found = ds
                        return@run
                    }
                }
                found
            }

        // Background / backdrop image
        val backdrop = doc.select("img[data-src*=w780]").attr("data-src").ifBlank { null }
            ?: doc.select("img[data-src*=w1280]").attr("data-src").ifBlank { null }

        return if (url.contains("/movies/")) {
            val playUrl = url.trimEnd('/') + "/play/"
            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = description
            }
        } else {
            // TV Show: gather episodes across seasons
            val seasonLinks = mutableListOf<Pair<String, String>>()
            doc.select("select[name='links'] option").forEach { opt ->
                val valS = opt.attr("value")
                if (valS.isNotBlank()) {
                    seasonLinks.add(opt.text() to valS)
                }
            }
            if (seasonLinks.isEmpty()) {
                doc.select("div.season-item a, a[href*=-S]").forEach { a ->
                    val href = a.attr("href")
                    val text = a.text()
                    if (href.isNotBlank()) seasonLinks.add(text to href)
                }
            }

            val episodes = mutableListOf<Episode>()
            if (seasonLinks.isEmpty()) {
                // Episodes listed directly on this page
                doc.select("div.episode-list a, ul.MovieList li.TPostMv a, a[href*=/play]").forEach { a ->
                    val href = a.attr("href")
                    val text = a.text()
                    if (href.isNotBlank() && text.isNotBlank()) {
                        episodes.add(
                            newEpisode(href) {
                                this.name = text
                            }
                        )
                    }
                }
            } else {
                seasonLinks.forEach { (label, seasonUrl) ->
                    val sDoc = app.get(httpsify(seasonUrl)).document
                    val seasonNum = label.substringAfterLast(" ").toIntOrNull()
                    sDoc.select("div.episode-item a, a[href*=/play], ul.MovieList li.TPostMv a").forEach { a ->
                        val href = a.attr("href")
                        val text = a.text()
                        if (href.isNotBlank() && text.isNotBlank()) {
                            episodes.add(
                                newEpisode(href) {
                                    this.name = text
                                    this.season = seasonNum
                                }
                            )
                        }
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        url: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playUrl = if (url.contains("/play/")) url else url.trimEnd('/') + "/play/"

        val doc = app.get(playUrl).document
        val sources = doc.select("video#my-video source, video source")

        var found = false
        sources.forEach { source ->
            val videoUrl = source.attr("src")
            val label = source.attr("label")
                .ifBlank { source.attr("data-res") }
                .ifBlank { source.attr("title") }
                .ifBlank { "Default" }

            val quality = when {
                label.contains("1080") -> Qualities.P1080.value
                label.contains("720") -> Qualities.P720.value
                label.contains("480") -> Qualities.P480.value
                label.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            if (videoUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink("SFlix", label, httpsify(videoUrl)) {
                        this.quality = quality
                    }
                )
                found = true
            }
        }

        return found
    }
}
