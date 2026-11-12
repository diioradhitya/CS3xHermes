package com.samehadaku

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * Server HTTP lokal (127.0.0.1) untuk menyajikan file mega.nz.
 *
 * Isi file mega dienkripsi end-to-end, jadi ExoPlayer tidak bisa memutar
 * URL download mega secara langsung. Server di sini:
 * 1. menerima request Range dari ExoPlayer,
 * 2. menerjemahkannya ke request Range ke server MEGA,
 * 3. mendekripsi isi file (AES-128-CTR, stream cipher -> bisa di-offset),
 * 4. mengirim hasilnya kembali sebagai 206 Partial Content.
 *
 * Server hanya mendengarkan di loopback dan memakai token acak per file,
 * jadi tidak bisa diakses dari luar perangkat.
 */
object MegaProxy {

    /** Batas data yang dikirim untuk request tanpa Range (preview thumbnail). */
    private const val MAX_PREVIEW_BYTES = 4L * 1024 * 1024

    private class Entry(val file: Mega.File, val embedUrl: String)

    private val entries = ConcurrentHashMap<String, Entry>()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var baseUrl: String = ""

    /**
     * Daftarkan file mega dan kembalikan URL lokal yang bisa diputar.
     */
    fun register(embedUrl: String, file: Mega.File): String {
        start()
        val token = UUID.randomUUID().toString().replace("-", "")
        entries[token] = Entry(file, embedUrl)
        return "$baseUrl/$token"
    }

    private fun start() {
        synchronized(this) {
            if (server != null) return
            val ss = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            server = ss
            baseUrl = "http://127.0.0.1:${ss.localPort}"
            thread(name = "mega-proxy", isDaemon = true) {
                while (true) {
                    try {
                        val client = ss.accept()
                        thread(name = "mega-proxy-conn", isDaemon = true) { handle(client) }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { sock ->
                sock.soTimeout = 30000
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val path = parts.getOrNull(1) ?: ""
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }

                val token = path.substringBefore('?').substringAfterLast('/')
                val entry = entries[token]
                if (entry == null) {
                    sendHeaders(output, 404, "text/plain", 0, null)
                    return
                }

                val range = headers["range"]
                val hasRange = !range.isNullOrBlank()
                val (start, requestedEnd) = parseRange(range, entry.file.size)
                var endExclusive =
                        when {
                            requestedEnd == null -> entry.file.size
                            requestedEnd >= entry.file.size -> entry.file.size
                            else -> requestedEnd + 1
                        }
                // Tanpa header Range (mis. pemuat preview thumbnail CloudStream)
                // Jangan tarik seluruh file (bisa 1-2 GB) - cukup 4 MB awal.
                if (!hasRange && endExclusive - start > MAX_PREVIEW_BYTES) {
                    endExclusive = start + MAX_PREVIEW_BYTES
                }
                if (start >= entry.file.size || endExclusive <= start) {
                    sendHeaders(output, 416, "text/plain", 0, "bytes */${entry.file.size}")
                    return
                }

                val contentType = contentType(entry.file.extension)
                sendHeaders(
                        output,
                        206,
                        contentType,
                        (endExclusive - start),
                        "bytes $start-${endExclusive - 1}/${entry.file.size}"
                )
                if (method == "HEAD") return

                runBlocking { streamDecrypted(entry, start, endExclusive, output) }
            }
        } catch (_: Exception) {
        }
    }

    /** Alir data terenkripsi dari MEGA -> dekripsi stream -> tulis ke client. */
    private suspend fun streamDecrypted(entry: Entry, start: Long, endExclusive: Long, output: OutputStream) {
        var file = entry.file
        var attempt = 0
        while (attempt < 2) {
            try {
                // CTR adalah stream cipher per blok 16 byte: untuk offset yang
                // tidak kelipatan 16, data harus diambil mulai batas blok lalu
                // byte sisanya dibuang setelah dekripsi.
                val skip = (start % 16).toInt()
                val alignedStart = start - skip
                val conn = URL(file.downloadUrl).openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 60000
                    setRequestProperty("Range", "bytes=$alignedStart-${endExclusive - 1}")
                    setRequestProperty("User-Agent", BROWSER_UA)
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("mega http $code")
                }
                conn.inputStream.use { raw ->
                    if (code == 200) {
                        // Server mengabaikan Range: datanya mulai dari 0.
                        skipBytes(raw, alignedStart)
                        decryptStream(raw, file, 0, 0, output)
                    } else {
                        decryptStream(raw, file, alignedStart, skip, output)
                    }
                }
                conn.disconnect()
                return
            } catch (e: Exception) {
                attempt++
                // URL download MEGA bisa kedaluwarsa -> ambil ulang dari API.
                val refreshed = runCatching { Mega.resolve(entry.embedUrl) }.getOrNull()
                if (refreshed == null || attempt >= 2) throw e
                file = refreshed
            }
        }
    }

    private fun skipBytes(raw: InputStream, count: Long) {
        var toSkip = count
        val buf = ByteArray(64 * 1024)
        while (toSkip > 0) {
            val n = raw.read(buf, 0, minOf(buf.size.toLong(), toSkip).toInt())
            if (n < 0) break
            toSkip -= n
        }
    }

    /** Dekripsi CTR streaming; offset = posisi byte pertama data di file. */
    private fun decryptStream(
            raw: InputStream,
            file: Mega.File,
            offset: Long,
            skip: Int,
            output: OutputStream
    ) {
        val blockIndex = offset / 16
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(file.aesKey, "AES"),
                IvParameterSpec(addCounter(file.counterBase, blockIndex))
        )
        var toSkip = skip
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = raw.read(buf)
            if (n < 0) break
            var out = cipher.update(buf, 0, n)
            if (toSkip > 0 && out.isNotEmpty()) {
                val drop = minOf(toSkip, out.size)
                out = out.copyOfRange(drop, out.size)
                toSkip -= drop
            }
            if (out.isNotEmpty()) {
                output.write(out)
                output.flush()
            }
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) {
            if (toSkip > 0) {
                val drop = minOf(toSkip, tail.size)
                if (drop < tail.size) output.write(tail, drop, tail.size - drop)
            } else {
                output.write(tail)
            }
        }
        output.flush()
    }

    private fun sendHeaders(
            output: OutputStream,
            code: Int,
            contentType: String,
            length: Long,
            contentRange: String?
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (contentRange != null) sb.append("Content-Range: ").append(contentRange).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray())
        output.flush()
    }

    private fun contentType(extension: String): String =
            when (extension) {
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "ts" -> "video/mp2t"
                "m3u8" -> "application/vnd.apple.mpegurl"
                else -> "video/mp4"
            }

    /** "bytes=100-200" -> (100, 200); null/parse gagal -> (0, null). */
    private fun parseRange(header: String?, size: Long): Pair<Long, Long?> {
        if (header.isNullOrBlank()) return 0L to null
        val m = Regex("""bytes=(\d*)-(\d*)""").find(header) ?: return 0L to null
        val startStr = m.groupValues[1]
        val endStr = m.groupValues[2]
        if (startStr.isEmpty()) {
            // bytes=-500 -> 500 byte terakhir
            val len = endStr.toLongOrNull() ?: return 0L to null
            val s = (size - len).coerceAtLeast(0L)
            return s to (size - 1).coerceAtLeast(0L)
        }
        val s = startStr.toLongOrNull() ?: return 0L to null
        val e = endStr.toLongOrNull()
        return s to e
    }

    private fun reason(code: Int): String =
            when (code) {
                200 -> "OK"
                206 -> "Partial Content"
                404 -> "Not Found"
                416 -> "Range Not Satisfiable"
                else -> "Error"
            }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(b.toChar())
        }
    }

    /** Tambah bilangan 128 bit big-endian ke counter CTR. */
    private fun addCounter(base: ByteArray, delta: Long): ByteArray {
        val out = base.copyOf()
        var carry = delta
        var i = out.size - 1
        while (i >= 0 && carry != 0L) {
            val sum = (out[i].toInt() and 0xFF) + (carry and 0xFF)
            out[i] = sum.toByte()
            carry = (carry shr 8) + (sum ushr 8)
            i--
        }
        return out
    }
}
