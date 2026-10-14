package com.layarasia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class LayarAsia : MainAPI() {
    override var mainUrl = "https://server-1.layar.asia"
    override var name = "LayarAsia"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "genre/drama-korea/page/%d/" to "Drama Korea",
        "genre/drama-china/page/%d/" to "Drama China",
        "genre/drama-thailand/page/%d/" to "Drama Thailand",
        "genre/drama-jepang/page/%d/" to "Drama Jepang",
        "genre/series-barat/page/%d/" to "Series Barat",
        "genre/movie/page/%d/" to "Movie",
        "genre/varshow/page/%d/" to "Variety Show",
        "genre/anime/page/%d/" to "Anime",
        "country/korea/page/%d/" to "Korea",
        "country/china/page/%d/" to "China",
        "country/thailand/page/%d/" to "Thailand",
        "country/japan/page/%d/" to "Japan",
        "year/2026/page/%d/" to "Terbaru 2026",
        "year/2025/page/%d/" to "Terbaru 2025",
        "type/drama/page/%d/" to "Drama",
        "type/tv-show/page/%d/" to "TV Show",
        "type/movie/page/%d/" to "Movie",
        "type/anime/page/%d/" to "Anime"
    )

    companion object {
        fun getType(t: String): TvType {
            return when (t.lowercase()) {
                "tv series", "tv show", "drama", "kdrama", "cdrama", "thai drama", "jdrama" -> TvType.TvSeries
                "movie", "film" -> TvType.Movie
                "anime" -> TvType.Anime
                "variety", "varshow" -> TvType.TvSeries
                else -> TvType.TvSeries
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t?.lowercase()) {
                "ongoing", "currently airing", "airing" -> ShowStatus.Ongoing
                "completed", "finished airing", "finished", "ended" -> ShowStatus.Completed
                else -> ShowStatus.Completed
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("article.item, article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h2.entry-title > a") ?: return null
        val title = titleEl.text().trim()
        val href = fixUrl(titleEl.attr("href"))

        val imgEl = selectFirst("div.content-thumbnail img, div.thumb img, figure img")
        val rawPoster = imgEl?.getImageAttr()
        val poster = fixUrlNull(rawPoster)?.fixImageQuality()

        val ratingText = selectFirst("div.gmr-rating-item, span.rating")?.ownText()?.trim()
        val rating = ratingText?.toFloatOrNull()

        val epsText = selectFirst("div.gmr-numbeps span, span.epx, div.type")?.text()?.trim()
        val eps = epsText?.toIntOrNull()

        val isSeries = eps != null || selectFirst("div.gmr-posttype-item, div.type")?.text()?.contains("TV", true) == true ||
                       selectFirst("div.type")?.text()?.contains("Drama", true) == true

        return if (isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                if (eps != null) addSub(eps)
                if (rating != null) this.score = Score.from10(rating)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                if (rating != null) this.score = Score.from10(rating)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/?s=$fixedQuery&post_type[]=post&post_type[]=tv"
        val document = app.get(searchUrl).document
        return document.select("article.item, article.bs, article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("h2.entry-title > a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("div.content-thumbnail img, figure img")?.getImageAttr())?.fixImageQuality()
        val rating = selectFirst(".gmr-rating-item, span.rating")?.ownText()?.trim()?.toFloatOrNull()
        val eps = selectFirst(".gmr-numbeps span, span.epx")?.text()?.trim()?.toIntOrNull()
        val isSeries = eps != null

        return if (isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                if (eps != null) addSub(eps)
                if (rating != null) this.score = Score.from10(rating)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                if (rating != null) this.score = Score.from10(rating)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.substringBefore("Season")?.substringBefore("Episode")?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("div.gmr-movie-data figure img, figure.pull-left img, div.thumb img")?.getImageAttr()?.fixImageQuality())
        val tags = document.select("div.gmr-movie-on a, div.gmr-moviedata a, div.genre-info a").map { it.text() }
        val year = document.select("time[itemprop=dateCreated], div.gmr-moviedata strong:contains(Year:) > a")
            .firstOrNull()?.text()?.trim()?.toIntOrNull()
        val tvType = if (url.contains("/tv/") || url.contains("/series/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p, div.gmr-moviedata p, div.entry-content > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav a.gmr-trailer-popup, a.trailer")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue], div.gmr-rating-item, span.rating")
            ?.text()?.trim()
        val actors = document.select("span[itemprop=director] a, span[itemprop=actors] a, div.gmr-moviedata span[itemprop=actors] a").map { it.text() }
        val duration = document.selectFirst("time[property=duration], span[property=duration], div.gmr-moviedata span[property=duration]")
            ?.text()?.replace(Regex("\\D"), "")?.toIntOrNull()
        val recommendations = document.select("article.item.col-md-20, article.bs").mapNotNull { it.toRecommendResult() }
        
        val statusText = document.selectFirst("span:contains(Status:) + a, div.alternati span:nth-child(2), div.gmr-moviedata:contains(Status)")?.text()?.trim()
        val showStatusVal = getStatus(statusText)

        val episodes = if (tvType == TvType.TvSeries) {
            document.select("div.vid-episodes a, div.gmr-listseries a, div.listupd a, div.episodelist a")
                .mapNotNull { eps ->
                    val href = fixUrl(eps.attr("href"))
                    val name = eps.text().trim()
                    
                    val episode = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(name)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("Eps(\\d+)", RegexOption.IGNORE_CASE).find(name)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("Ep\\.?\\s*(\\d+)", RegexOption.IGNORE_CASE).find(name)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val season = Regex("S(\\d+)", RegexOption.IGNORE_CASE).find(name)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()

                    if (episode != null) {
                        newEpisode(href) {
                            this.name = "Episode $episode"
                            this.episode = episode
                            this.season = season
                            this.posterUrl = poster
                        }
                    } else null
                }.filter { it.episode != null }
        } else emptyList()

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
                showStatus = showStatusVal
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Try to find iframe sources from the video player section
        document.select("div.gmr-embed-responsive iframe, div.player-area iframe, div#player iframe, div.muvipro-player iframe").forEach { frame ->
            val iframe = frame.getIframeAttr()?.let { httpsify(it) } ?: return@forEach
            loadExtractor(iframe, data, subtitleCallback, callback)
        }

        // Try to find server tabs (UPNShare, HydraX, StreamP2P)
        document.select("ul.muvipro-player-tabs li a[href^=#], div.server-tabs a[href^=#]").forEach { tab ->
            val tabId = tab.attr("href").removePrefix("#")
            val iframe = document
                .selectFirst("div#$tabId iframe, div.tab-content#$tabId iframe")
                ?.getIframeAttr()
                ?.let { httpsify(it) }

            if (iframe != null) {
                loadExtractor(iframe, data, subtitleCallback, callback)
            }
        }

        // Try AJAX load for muvipro player (common in this theme)
        val id = document.selectFirst("div#muvipro_player_content_id, div[data-post-id]")?.attr("data-id") ?: 
                 document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        
        if (id != null && document.select("iframe").isEmpty()) {
            document.select("div.tab-content-ajax, div.tab-pane").forEach { ele ->
                val tabId = ele.attr("id")
                val serverUrl = "$mainUrl/wp-admin/admin-ajax.php"

                val res = app.post(
                    serverUrl,
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to tabId,
                        "post_id" to id
                    )
                ).document
                    .selectFirst("iframe")
                    ?.attr("src")
                    ?.let { httpsify(it) }

                if (!res.isNullOrEmpty()) {
                    loadExtractor(res, data, subtitleCallback, callback)
                }
            }
        }

        // Handle download links - Terabox, Mirrored, FileMoon, Fileditch
        document.select("ul.gmr-download-list li a, div.download-links a, a[href*='terabox'], a[href*='filemoon'], a[href*='fileditch'], a[href*='mirrored']").forEach { link ->
            val downloadUrl = link.attr("href")
            if (downloadUrl.isNotBlank()) {
                loadExtractor(downloadUrl, data, subtitleCallback, callback)
            }
        }

        // Also check for direct video links in the content
        document.select("a[href*='.mp4'], a[href*='.m3u8'], a[href*='googlevideo'], a[href*='drive.google']").forEach { link ->
            val videoUrl = link.attr("href")
            if (videoUrl.isNotBlank()) {
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") && attr("data-src").isNotBlank() -> attr("abs:data-src")
            hasAttr("data-lazy-src") && attr("data-lazy-src").isNotBlank() -> attr("abs:data-lazy-src")
            hasAttr("srcset") && attr("srcset").isNotBlank() -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") && attr("src").isNotBlank() -> attr("abs:src")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? =
        this?.attr("data-litespeed-src").takeIf { !it.isNullOrEmpty() } ?: this?.attr("src") ?: this?.attr("data-src")

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.value ?: return this
        return replace(regex, "")
    }

    private fun getBaseUrl(url: String): String =
        URI(url).let { "${it.scheme}://${it.host}" }
}