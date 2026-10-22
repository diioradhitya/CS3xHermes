package com.oploverz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * User-Agent browser. Tanpa ini, WAF situs (Cloudflare) menolak request
 * dari client HTTP bawaan (User-Agent "okhttp/..."), dan halaman yang
 * sebenarnya tersedia dianggap gagal diakses.
 */
internal const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Cek apakah sebuah link media benar-benar bisa diambil.
 * Range 1 KB supaya tidak mengunduh seluruh video. Link yang sudah mati
 * (Blogger/embedder balas 403/400) dibuang supaya app tidak memilih
 * sumber yang gagal.
 */
internal suspend fun isMediaLinkAlive(url: String, referer: String? = null): Boolean {
    return try {
        val r =
                app.get(
                        url,
                        referer = referer,
                        headers =
                                mapOf(
                                        "User-Agent" to BROWSER_UA,
                                        "Range" to "bytes=0-1023"))
        val code = r.code
        (code == 200 || code == 206)
    } catch (e: Throwable) {
        false
    }
}
