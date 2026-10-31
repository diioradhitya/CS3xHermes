package com.samehadaku

import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** User-Agent standar untuk request ke situs sumber. */
const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 10; Redmi Note 8 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * Cek apakah media link masih hidup (HEAD + Range 1 KB).
 * HTTP 200/206/416 dianggap hidup. Retry 2x dengan jeda 300 ms.
 * @param referer digunakan sebagai header Referer (beberapa server memvalidasi).
 */
suspend fun isMediaLinkAlive(url: String, referer: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            var alive = false
            for (attempt in 0..2) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.apply {
                        requestMethod = "HEAD"
                        instanceFollowRedirects = true
                        connectTimeout = 10000
                        readTimeout = 10000
                        setRequestProperty("Range", "bytes=0-1023")
                        setRequestProperty("User-Agent", BROWSER_UA)
                        referer?.let { setRequestProperty("Referer", it) }
                    }
                    val code = conn.responseCode
                    alive = code in listOf(200, 206, 416)
                    conn.disconnect()
                    if (alive) break
                } catch (_: IOException) {
                    // retry
                }
                if (attempt < 2) {
                    Thread.sleep(300)
                }
            }
            alive
        }
