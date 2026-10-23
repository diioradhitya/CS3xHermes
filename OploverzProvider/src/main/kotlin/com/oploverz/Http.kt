package com.oploverz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay

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
 *
 * Retry: blogger/googlevideo kadang balas 403/400 sementara (throttle saat
 * token baru dibuat), lalu normal lagi, jadi coba 2x sebelum menyatakan
 * mati. 416 ikut dianggap hidup karena server itu tahu file-nya (range yang
 * diminta cuma 1 KB pertama).
 */
internal suspend fun isMediaLinkAlive(
        url: String,
        referer: String? = null,
        attempts: Int = 2
): Boolean {
    for (attempt in 1..attempts) {
        try {
            val r =
                    app.get(
                            url,
                            referer = referer,
                            timeout = 10,
                            headers =
                                    mapOf(
                                            "User-Agent" to BROWSER_UA,
                                            "Range" to "bytes=0-1023"))
            val code = r.code
            if (code == 200 || code == 206 || code == 416) return true
        } catch (e: Throwable) {
        }
        if (attempt < attempts) delay(300)
    }
    return false
}
