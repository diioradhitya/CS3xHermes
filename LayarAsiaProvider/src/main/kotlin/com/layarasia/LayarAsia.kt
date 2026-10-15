package com.layarasia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64

class LayarAsia : MainAPI() {
    override var mainUrl = "https://server-1.layar.asia"
    override var name = "LayarAsia"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    // Mirrors the seven sections the site's own home page renders, using each
    // section's "View All" target so ordering matches what the site shows
    // (most recently updated / added) instead of an alphabetical slug browse.
    // Pagination is &page=<n>; the site returns a fresh set of 20 each time.
    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update&page=%d" to "Baru Dirilis",
        "series/?type=movie&order=latest&page=%d" to "Movie",
        "series/?genre%5B%5D=drama-korea&status=&type=&order=update&page=%d" to "Drama Korea",
        "series/?country%5B%5D=china&status=&type=&order=update&page=%d" to "Drama China",
        "series/?genre%5B%5D=varshow&order=update&page=%d" to "Variety Show",
        "series/?genre%5B%5D=series-barat&order=update&page=%d" to "Series Barat",
        "series/?country%5B%5D=thailand&status=&type=&order=update&page=%d" to "Series Thailand"
    )

    companion object {
        private val EPISODE_IN_TEXT = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)

        fun getType(t: String): TvType = when (t.lowercase()) {
            "movie", "film" -> TvType.Movie
            "anime", "donghua" -> TvType.Anime
            "tv series", "tv show", "drama", "kdrama", "cdrama", "thai drama",
            "jdrama", "dorama", "mini series", "series", "variety", "varshow" -> TvType.TvSeries
            else -> TvType.TvSeries
        }

        fun getStatus(t: String?): ShowStatus = when (t?.lowercase()?.trim()) {
            "ongoing", "currently airing", "airing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.selectFirst("div.listupd")?.select("article.bs")
            ?.mapNotNull { it.toSearchResult() }
            .orEmpty()
        return newHomePageResponse(request.name, items)
    }

    /**
     * Cards look like this on the live site:
     *   article.bs > .bsx > a.tip[href,title] > .limit(.countrybadge,.typez,.ply,.bt>span.epx,img) + .ttt>.tt
     * There is no h2.entry-title and no div.content-thumbnail.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.tip[href]") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = link.attr("title").trim().ifEmpty {
            selectFirst(".tt[itemprop=headline], .tt, h2[itemprop=headline], h2.entry-title")
                ?.text()?.trim().orEmpty()
        }
        if (title.isEmpty()) return null

        val poster = fixUrlNull(selectFirst("img")?.getImageAttr())?.fixImageQuality()

        val ratingText = selectFirst(".upscore, span.rating, div.gmr-rating-item")?.ownText()?.trim()
        val rating = ratingText?.removeSuffix("/10")?.trim()?.toFloatOrNull()

        val epsText = selectFirst("span.epx")?.text()?.trim()
        val eps = epsText?.let { EPISODE_IN_TEXT.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val typeLabel = selectFirst("div.typez")?.text()?.trim()
        val tvType = when {
            eps != null -> TvType.TvSeries
            typeLabel != null -> getType(typeLabel)
            href.contains("/series/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                if (rating != null) this.score = Score.from10(rating)
            }
        } else {
            newAnimeSearchResponse(title, href, tvType) {
                posterUrl = poster
                if (eps != null) addSub(eps)
                if (rating != null) this.score = Score.from10(rating)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val document = app.get("$mainUrl/?s=$fixedQuery&post_type[]=post").document
        return document.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = (document.selectFirst("h1.entry-title[itemprop=name], h1.entry-title")?.text()
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: "")
            .replace(Regex("\\s*(Season|Episode)\\b.*$", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = fixUrlNull(document.selectFirst("div.thumbook img, figure img, div.tb img")?.getImageAttr())
            ?.fixImageQuality()

        // Metadata lives in div.spe as "<b>Label:</b> value" pairs.
        val info = document.selectFirst("div.info-content div.spe")?.allElements
            ?.mapNotNull { row ->
                val label = row.selectFirst("b")?.text()?.removeSuffix(":")?.trim() ?: return@mapNotNull null
                val value = row.ownText() + row.select("a").joinToString("") { it.ownText() }
                label to value.trim()
            }?.toMap()
            .orEmpty()

        val tags = document.select("div.spe a[href*=/genres/], a[href*=/country/], a[href*=/network/]")
            .map { it.text().trim() }
            .distinct()

        val year = Regex("\\b(19|20)\\d{2}\\b").findAll(title)
            .lastOrNull()
            ?.value
            ?.toIntOrNull()

        val typeLabel = info["Tipe"]
        val tvType = when {
            url.contains("/series/") -> TvType.TvSeries
            typeLabel != null -> getType(typeLabel)
            else -> TvType.Movie
        }

        val description = document.selectFirst("div.mindesc")?.text()?.trim()
        val rating = Regex("(\\d+(?:[.,]\\d+)?)\\s*/\\s*10").find(title)?.groupValues?.getOrNull(1)
            ?.replace(",", ".")?.toDoubleOrNull()?.toFloat()

        val actors = document.select("div.spe span:contains(Artis) a").map { it.text().trim() }

        val duration = info["Durasi"]?.let { raw ->
            Regex("(\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.times(60)
        } ?: info["Durasi"]?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val showStatusVal = getStatus(info["Status"])

        val recommendations = document.select("article.bs").mapNotNull { it.toSearchResult() }

        val episodes = if (tvType == TvType.TvSeries) {
            // Both the "latest" strip (div.lastend) and the full table
            // (div.eplister) link the same episode pages, so dedupe on href
            // before mapping to keep each episode once.
            val pairs = document
                .select("div.eplister ul li a[href], div.lastend div.inepcx a[href]")
                .mapNotNull { eps ->
                    val raw = eps.attr("href")
                    if (raw.isBlank() || raw == "#") return@mapNotNull null
                    val href = fixUrl(raw)
                    val label = eps.text()
                    val episode = eps.selectFirst(".epl-num")?.text()?.trim()?.toIntOrNull()
                        ?: EPISODE_IN_TEXT.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: return@mapNotNull null
                    val season = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("S(\\d+)(?:E\\d+)?$", RegexOption.IGNORE_CASE)
                            .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    Triple(href, episode, season)
                }
                .distinctBy { it.first }

            pairs.map { (href, episode, season) ->
                newEpisode(href) {
                    this.name = "Episode $episode"
                    this.episode = episode
                    this.season = season
                    this.posterUrl = poster
                }
            }.sortedBy { it.episode ?: Int.MAX_VALUE }
        } else emptyList()

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                if (rating != null) addScore(rating.toString())
                addActors(actors)
                this.recommendations = recommendations
                if (duration != null) this.duration = duration
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                if (rating != null) addScore(rating.toString())
                addActors(actors)
                this.recommendations = recommendations
                if (duration != null) this.duration = duration
                showStatus = showStatusVal
            }
        }
    }

    /**
     * The embed is a plain iframe inside div.player-embed. Each additional
     * server is a <select class="mirror"> option whose value attribute is
     * base64 of the iframe HTML, so they are decoded here rather than left to
     * the extractors.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val found = mutableSetOf<String>()

        // Suspend local function is not allowed, so take a suspend lambda.
        val emit: suspend (String) -> Unit = { iframeHtml ->
            val src = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .find(iframeHtml)?.groupValues?.getOrNull(1)?.trim()
            if (!src.isNullOrBlank() && found.add(src)) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        document.select("div.player-embed iframe, div#pembed iframe, div.video-content iframe")
            .forEach { it.getIframeAttr()?.let { src -> emit(src) } }

        document.select("select.mirror option[value], select[name=mirror] option[value]")
            .forEach { opt ->
                val encoded = opt.attr("value")
                if (encoded.length < 20) return@forEach
                val decoded = runCatching {
                    String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                }.getOrNull() ?: return@forEach
                if (decoded.contains("iframe", ignoreCase = true)) emit(decoded)
            }

        document.select("a[href*='terabox'], a[href*='filemoon'], a[href*='fileditch'], a[href*='mirror'], a[href$='.mp4'], a[href*='.m3u8']")
            .forEach { link ->
                val href = link.attr("href").trim()
                if (href.isNotBlank()) loadExtractor(fixUrl(href), data, subtitleCallback, callback)
            }

        return true
    }

    private fun Element.getImageAttr(): String? = when {
        hasAttr("data-lazy-src") && attr("data-lazy-src").isNotBlank() -> attr("abs:data-lazy-src")
        hasAttr("data-src") && attr("data-src").isNotBlank() -> attr("abs:data-src")
        hasAttr("srcset") && attr("srcset").isNotBlank() -> attr("abs:srcset").substringBefore(" ")
        hasAttr("src") && attr("src").isNotBlank() -> attr("abs:src")
        else -> null
    }

    private fun Element?.getIframeAttr(): String? =
        this?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")?.takeIf { it.isNotBlank() }

    private fun String.fixImageQuality(): String = replace(Regex("(-\\d+x\\d*)"), "")
}
