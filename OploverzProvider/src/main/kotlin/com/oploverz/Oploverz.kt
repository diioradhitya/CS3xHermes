package com.oploverz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Oploverz (anime sub Indo).
 *
 * SITUS BARU (tema WordPress "Madara"):
 * - Listing anime : /anime/?status=&type=&order=update[&page=N]
 * - Halaman anime : /anime/<slug>/
 * - Halaman episode: /<slug>-episode-<N>-subtitle-indonesia/  (iframe player inline)
 * - Pencarian     : /?s=<query>
 *
 * DOM baru (beda total dari situs lama anime.oploverz.ac yang sudah mati):
 * - item listing  : <article class="bs"> > div.bsx > a[href] + img[itemprop=image]
 *                    + div.typez (tipe) + span.epx (status) + h2 (judul)
 * - halaman anime : h1.entry-title, div.typez, span.status, div.eplister > li > a > div.epl-num
 * - episode       : <iframe src="https://upbolt.to/e/..."> (diparse langsung)
 *
 * Domain yang dipakai (fallback berurutan, otomatis pindah kalau satu mati):
 * oploverz.am -> oploverz.ch -> oploverz.site
 * Catatan: anime.oploverz.ac DAN oploverz.site sekarang resolve ke IP
 * blockpage.xlaxiata.id (halaman blokir) => tidak bisa dipakai, tapi tetap
 * dicoba sebagai fallback terakhir.
 */
class Oploverz : MainAPI() {
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val hosts = listOf(
            "https://oploverz.am",
            "https://oploverz.ch",
            "https://oploverz.site"
    )

    /** Index host yang sedang dipakai; naik setiap kali ada host gagal. */
    @Volatile private var hostIndex = 0

    override var mainUrl: String = hosts[0]

    companion object {
        fun getType(t: String): TvType {
            val s = t.trim()
            return when {
                s.contains("OVA", true) || s.contains("Special", true) -> TvType.OVA
                s.contains("Movie", true) || s.contains("Film", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t?.trim()) {
                "Ongoing", "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage =
            mainPageOf(
                    "update" to "Latest Update",
                    "latest" to "Latest Added",
                    "popular" to "Popular Anime",
                    "rating" to "Top Rated",
            )

    /**
     * Ambil halaman dengan failover antar domain.
     *
     * @param marker potongan DOM yang wajib ada supaya response dianggap valid
     * (mencegah 404 / halaman blokir dianggap erfolgre).
     */
    private suspend fun fetchDoc(path: String, marker: String): Document {
        var lastError: Throwable? = null
        // coba semua host, mulai dari yang terakhir berhasil
        for (offset in hosts.indices) {
            val idx = (hostIndex + offset) % hosts.size
            val host = hosts[idx]
            // URL absolut dipakai apa adanya (bukan ditukar host), supaya
            // halaman yang diketahui dari domain lain tidak diambil dari domain
            // yang salah.
            val target = if (path.startsWith("http")) path else host + path
            try {
                val doc = app.get(target).document
                if (doc.body() != null && doc.html().contains(marker, ignoreCase = true)) {
                    hostIndex = idx
                    mainUrl = host
                    return doc
                }
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: Exception("Oploverz: semua domain gagal diakses")
    }

    /** Link anime canonical: /anime/<slug>/ */
    private fun animeLink(raw: String): String {
        val path = raw.substringAfter("://").substringAfter('/')
        if (path.startsWith("anime/")) return raw
        var slug = path.substringBefore('/').substringBefore('?')
        // slug episode: "bleach-episode-415-subtitle-indonesia" -> "bleach"
        slug = slug.replace(Regex("-(episode|episode-\\d+)(-\\d+)?-subtitle-indonesia$"), "")
        slug = slug.replace(Regex("-subtitle-indonesia$"), "")
        slug = slug.replace(Regex("-episode-\\d+$"), "")
        return "$mainUrl/anime/$slug/"
    }

    private fun Element.animeTitle(): String {
        val tt = selectFirst("div.tt")?.text()?.trim().orEmpty()
        if (tt.isNotEmpty() && !tt.contains("Episode", true)) return tt
        val h2 = selectFirst("h2")?.text()?.trim().orEmpty()
        return h2.replace(Regex("\\s*Episode\\s*\\d+\\s*Subtitle\\s*Indonesia", RegexOption.IGNORE_CASE), "")
                .trim()
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href")?.takeIf { it.isNotEmpty() } ?: return null
        val title = animeTitle().ifEmpty { return null }
        val posterUrl = selectFirst("img[itemprop=image]")?.attr("src") ?: selectFirst("img")?.attr("src")
        val type = getType(selectFirst("div.typez")?.text() ?: selectFirst("div.type")?.text() ?: "")
        return newAnimeSearchResponse(title, animeLink(href), type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetchDoc("/anime/?status=&type=&order=${request.data}&page=$page", "article")
        val home = doc.select("article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val anime = mutableListOf<SearchResponse>()
        for (page in 1..2) {
            val doc = fetchDoc("/?s=$query&page=$page", "article")
            val media = doc.select("article.bs").mapNotNull { it.toSearchResult() }
            if (media.isEmpty()) break
            anime.addAll(media)
        }
        return anime
    }

    /** Ambil nilai field dari info box Madara: <span><b>Type:</b> TV</span> */
    private fun Element.infoField(label: String): String? =
            select("span")
                    .firstOrNull {
                        it.selectFirst("b")?.text()?.trim()?.removeSuffix(":")?.equals(label, true) == true
                    }
                    ?.ownText()
                    ?.trim()
                    ?.replace(Regex("^$label\\s*:\\s*", RegexOption.IGNORE_CASE), "")
                    ?.trim()
                    ?.ifEmpty { null }

    /** Section "Recommended Series" (div.bixbox > div.listupd) di halaman Madara. */
    private fun Element.recommendedItems(): List<SearchResponse> {
        val box =
                select("div.bixbox").firstOrNull {
                    it.selectFirst("h3 span")?.text()?.contains("Recommended", true) == true
                } ?: return emptyList()
        val list = box.selectFirst("div.listupd") ?: return emptyList()
        return list.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url, "eplister")

        val title =
                doc.selectFirst("h1.entry-title")
                        ?.text()
                        ?.replace(Regex("Subtitle Indonesia", RegexOption.IGNORE_CASE), "")
                        ?.trim()
                        ?: ""
        val info = doc.selectFirst("div.info-content")
        val type =
                getType(
                        info?.infoField("Type")
                                ?: doc.selectFirst("div.typez")?.text()
                                ?: ""
                )
        val statusText = info?.infoField("Status") ?: doc.selectFirst("span.status")?.text()?.trim()
        val year = info?.infoField("Released")?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        // daftar episode: <li data-index><a href><div class="epl-num">N</div>
        val epItems =
                doc.select("div.eplister li").mapNotNull { li ->
                    val a = li.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")
                    if (href.isEmpty()) return@mapNotNull null
                    val num =
                            li.selectFirst("div.epl-num")
                                    ?.text()
                                    ?.trim()
                                    ?.toIntOrNull()
                                    ?: a.text().trim().toIntOrNull()
                    Triple(fixUrl(href), num, li.selectFirst("div.epl-title")?.text()?.trim())
                }.sortedBy { it.second ?: 0 }

        val episodes =
                epItems.map { (link, num, epTitle) ->
                    newEpisode(link) {
                        this.episode = num
                        this.name = epTitle
                    }
                }

        val tracker =
                runCatching { APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true) }
                        .getOrNull()
        val poster = tracker?.image ?: doc.selectFirst("div.thumb > img")?.attr("src")
        val cover = tracker?.cover
        val plot = doc.selectFirst("div.entry-content > p")?.text()?.trim()
        val genres = doc.select("div.genxed a").map { it.text() }
        val recommendations = doc.recommendedItems()
        val status = getStatus(statusText)

        // Film (Movie) diserialisasi CloudStream sebagai MovieLoadResponse: tidak
        // ada daftar episode, tapi punya dataUrl. Untuk film, halaman /anime/xxx
        // cuma daftar; player-nya ada di post episode (1 entri eplister).
        if (type == TvType.AnimeMovie || type == TvType.Movie) {
            val dataUrl = epItems.firstOrNull()?.first ?: url
            return newMovieLoadResponse(name = title, url = url, type = type, dataUrl = dataUrl) {
                posterUrl = poster
                backgroundPosterUrl = cover
                this.year = year
                this.plot = plot
                this.tags = genres
                this.recommendations = recommendations
                runCatching { addMalId(tracker?.malId) }
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            backgroundPosterUrl = cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            this.plot = plot
            this.tags = genres
            this.recommendations = recommendations
            runCatching { addMalId(tracker?.malId) }
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = fetchDoc(data, "iframe")

        // Player Madara ada di <div id="embed_holder"><div id="pembed"><iframe ...>
        // Tiap episode bisa punya embedder berbeda (upbolt.to, blogger.com,
        // filedon.co, ...), jadi semua iframe dicoba, bukan cuma yang pertama.
        val iframes =
                doc.select("#pembed iframe[src], #embed_holder iframe[src], iframe[src]")
                        .map { it.attr("src") }
                        .filter { it.isNotEmpty() }
                        .distinct()
        if (iframes.isEmpty()) return false

        var found = 0
        val collect: (ExtractorLink) -> Unit = { link ->
            found++
            callback(link)
        }
        for (iframe in iframes) {
            // referer = URL halaman episode (bukan root situs): sebagian
            // embedder (blogger, dll) memvalidasi referer ini.
            loadExtractor(fixUrl(iframe), data, subtitleCallback, collect)
        }
        return found > 0
    }
}
