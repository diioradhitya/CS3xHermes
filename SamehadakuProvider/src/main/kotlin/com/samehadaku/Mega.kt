package com.samehadaku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dukungan mega.nz.
 *
 * File Mega dienkripsi end-to-end (AES-128-CTR untuk isi file, AES-128-CBC
 * untuk atribut). Link publik `mega.nz/embed/<id>#<key>` portanto TIDAK bisa
 * langsung diputar ExoPlayer: yang dilayani server adalah data terenkripsi.
 *
 * Alur yang dipakai di sini:
 * 1. Ambil metadata file lewat API MEGA:
 *    POST https://g.api.mega.co.nz/cs  body  [{"a":"g","g":1,"p":"<fileId>"}]
 *    Balasan: [{"s":<ukuran>,"at":<atribut terenkripsi>,"g":<url download>}]
 * 2. Dekripsi atribut (AES-128-CBC, IV nol, key = fileKey[0..3] XOR fileKey[4..7])
 *    -> nama file asli (dipakai untuk ekstensi/content-type).
 * 3. Isi file didekripsi AES-128-CTR dengan key = fileKey[0..3] XOR fileKey[4..7]
 *    dan counter awal dari fileKey[4..5]. Karena CTR adalah stream cipher,
 *    potongan (range) mana pun bisa didekripsi dengan menggeser counter.
 *
 * Datanya sendiri disajikan lewat [MegaProxy] (server HTTP lokal) supaya
 * ExoPlayer bisa melakukan request Range seperti biasa.
 */
object Mega {

    private const val API = "https://g.api.mega.co.nz/cs"

    /** Satu file Mega yang sudah diketahui URL download & key-nya. */
    class File(
            val id: String,
            /** 32 byte file key dari fragment link. */
            val key: ByteArray,
            val downloadUrl: String,
            val size: Long,
            val name: String
    ) {
        /** Key AES-128 (16 byte) = key[0..3] XOR key[4..7]. */
        val aesKey: ByteArray = ByteArray(16)

        /** Counter awal 128 bit untuk AES-CTR. */
        val counterBase: ByteArray = ByteArray(16)

        init {
            val w = words(key)
            for (i in 0 until 4) {
                val v = w[i] xor w[i + 4]
                aesKey[i * 4] = (v ushr 24).toByte()
                aesKey[i * 4 + 1] = (v ushr 16).toByte()
                aesKey[i * 4 + 2] = (v ushr 8).toByte()
                aesKey[i * 4 + 3] = v.toByte()
            }
            // counter awal = ((w4 << 32) + w5) << 64  ->  16 byte big-endian
            putInt(counterBase, 0, w[4])
            putInt(counterBase, 4, w[5])
        }

        val extension: String
            get() {
                val n = name.substringBefore('?')
                val dot = n.lastIndexOf('.')
                return if (dot >= 0) n.substring(dot + 1).lowercase() else "mp4"
            }
    }

    /** Pecah link embed/file/folder mega menjadi id file + key (fragment). */
    fun parseLink(url: String): Pair<String, String?>? {
        val id =
                when {
                    url.contains("/embed/") -> url.substringAfter("/embed/")
                    url.contains("/file/") -> url.substringAfter("/file/")
                    url.contains("#!") -> url.substringAfter("#!").substringBefore('!')
                    else -> return null
                }.substringBefore('#').substringBefore('!').substringBefore('?').substringBefore('/')
        if (id.isBlank()) return null
        val key =
                when {
                    url.contains('#') -> url.substringAfter('#').substringBefore('!').substringBefore('?')
                    else -> null
                }
        return id to key
    }

    private fun parseHandle(url: String): String? = parseLink(url)?.first

    /**
     * Resolve link publik mega jadi file yang bisa didekripsi.
     * Null kalau file sudah tidak ada / API menolak.
     */
    suspend fun resolve(embedUrl: String): File? =
            withContext(Dispatchers.IO) {
                val (id, keyPart) = parseLink(embedUrl) ?: return@withContext null
                val body = apiRequest("""[{"a":"g","g":1,"p":"$id"}]""")
                val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext null
                val obj = arr.optJSONObject(0) ?: return@withContext null
                val url = obj.optString("g").takeIf { it.isNotBlank() } ?: return@withContext null
                val size = obj.optLong("s", 0L)
                val attrs = obj.optString("at")
                val keyBytes = decodeKey(keyPart) ?: return@withContext null
                val name = decryptAttributes(attrs, keyBytes) ?: "$id.mp4"
                File(id, keyBytes, url, size, name)
            }

    /** Panggil API MEGA, kembalikan body apa adanya. */
    private fun apiRequest(body: String): String {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", BROWSER_UA)
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return text
    }

    /** Base64url (tanpa padding) -> 32 byte file key. */
    private fun decodeKey(part: String?): ByteArray? {
        if (part.isNullOrBlank()) return null
        return b64urlDecode(part).takeIf { it.size == 32 }
    }

    /** Dekripsi atribut file (nama asli) dengan AES-128-CBC IV nol. */
    private fun decryptAttributes(attrsBase64: String, key: ByteArray): String? {
        if (attrsBase64.isBlank()) return null
        return runCatching {
            val raw = b64urlDecode(attrsBase64)
            val w = words(key)
            val aesKey = ByteArray(16)
            for (i in 0 until 4) {
                val v = w[i] xor w[i + 4]
                aesKey[i * 4] = (v ushr 24).toByte()
                aesKey[i * 4 + 1] = (v ushr 16).toByte()
                aesKey[i * 4 + 2] = (v ushr 8).toByte()
                aesKey[i * 4 + 3] = v.toByte()
            }
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
            val dec = cipher.doFinal(raw)
            val json = String(dec, 4, dec.size - 4, Charsets.UTF_8).trimEnd('\u0000')
            val name = org.json.JSONObject(json).optString("n")
            name.ifBlank { null }
        }.getOrNull()
    }

    /** Ambil potongan file (terenkripsi) dari server MEGA. */
    fun fetchEncrypted(file: File, start: Long, end: Long): ByteArray {
        val conn = URL(file.downloadUrl).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Range", "bytes=$start-$end")
            setRequestProperty("User-Agent", BROWSER_UA)
        }
        val code = conn.responseCode
        val body = if (code in 200..299) conn.inputStream else conn.errorStream
        val out = ByteArrayOutputStream()
        body?.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        conn.disconnect()
        return out.toByteArray()
    }

    /**
     * Decoder base64url tanpa padding (menggantikan android.util.Base64
     * supaya tidak bergantung pada API level dan bisa diuji di JVM).
     */
    private fun b64urlDecode(data: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val clean = data.trimEnd('=')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = alphabet.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    // --- helper ---

    private fun words(bytes: ByteArray): IntArray {
        val w = IntArray(8)
        for (i in 0 until 8) {
            var v = 0
            for (j in 0 until 4) v = (v shl 8) or (bytes[i * 4 + j].toInt() and 0xFF)
            w[i] = v
        }
        return w
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

}
