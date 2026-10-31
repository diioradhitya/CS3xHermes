package com.samehadaku

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Validator Mega.nz.
 * Cek apakah file di Mega.nz masih ada melalui API g.api.mega.co.nz/cs.
 */
object Mega {

    /**
     * Cek apakah file Mega masih alive.
     * Balasan objek ({"g":...}) = hidup; kode negatif [-9]/[-11] = mati.
     * Kalau API tidak bisa dihubungi → anggap hidup.
     */
    suspend fun isFileAlive(megaUrl: String): Boolean =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val handle = parseHandle(megaUrl) ?: return@withContext true
            try {
                val url = URL("https://g.api.mega.co.nz/cs")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    doOutput = true
                    instanceFollowRedirects = true
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", BROWSER_UA)
                }
                val body = "[{\"a\":\"g\",\"p\":\"$handle\"}]"
                conn.outputStream.write(body.toByteArray())
                conn.outputStream.flush()
                conn.outputStream.close()
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                resp.contains("\"g\"") && !resp.contains("[-9]") && !resp.contains("[-11]")
            } catch (_: Exception) {
                true // API tidak bisa diakses, anggap hidup
            }
        }

    private fun parseHandle(url: String): String? {
        return when {
            url.contains("/embed/") -> {
                val part = url.substringAfter("/embed/")
                part.takeWhile { it != '#' && it != '!' && it != '?' && it != '/' }
            }
            url.contains("/file/") -> {
                val part = url.substringAfter("/file/")
                part.takeWhile { it != '#' && it != '!' && it != '?' && it != '/' }
            }
            url.contains("#!") -> {
                url.substringAfter("#!").takeWhile { it != '!' && it != '?' && it != '/' }
            }
            else -> null
        }
    }
}
