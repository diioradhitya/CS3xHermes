package com.oploverz

import com.lagradost.cloudstream3.app

/**
 * Validator mega.nz (BUKAN player).
 *
 * CloudStream tidak punya extractor pemutar untuk mega.nz: file di-serve
 * terenkripsi AES-CBC dan hanya bisa didekrip di client (browser/aplikasi
 * Mega), jadi link Mega tetap tidak bisa diputar langsung di dalam app -
 * tapi link-nya bisa di-copy ke downloader/aplikasi Mega (catatan
 * maintenance #1).
 *
 * Yang DAPAT diperbaiki di plugin: link Mega yang filenya sudah terhapus
 * atau dinonaktifkan. CloudStream dulu tetap menampilkan entri tersebut,
 * dan setiap kali dipilih muncul Error 3003 karena app menganggap URL
 * mega.nz/embed adalah HTML biasa. Validator ini menanyakan API publik
 * mega ([{"a":"g","p":<handle>}]) lalu:
 *   - file masih ada  -> balasan berupa objek JSON (ada field "g"/"s"/"at")
 *   - file sudah mati -> balasan berupa kode error negatif: [-9] ENOENT,
 *     [-11] EACCESS, dst -> link dibuang dari daftar sumber.
 */
internal object Mega {
    private const val API = "https://g.api.mega.co.nz/cs?id="

    /** Penomoran request mega: monoton naik cukup (tidak harus unik). */
    @Volatile private var seq: Int = 0

    /**
     * Ambil (public) file handle dari URL mega. Format yang diketahui:
     *   https://mega.nz/embed/<handle>#<key>...   (embed umum di samehadaku)
     *   https://mega.nz/embed/<handle>!<key>      (embed gaya lama)
     *   https://mega.nz/file/<handle>#<key>       / file/<handle>#!<key>
     *   https://mega.nz/#!<handle>!<key>          (format lama, tanpa path)
     * Kalau tidak cocok, kembalikan null - caller DIBIARKAN meng-emit link
     * seperti biasa (graceful, jangan buang link yang mungkin valid).
     */
    fun fileHandle(link: String): String? {
        if (!link.contains("mega.nz", ignoreCase = true)) return null
        val m =
                Regex("""mega\.nz/(?:file|embed|n|scene)/([A-Za-z0-9_-]{6,})""")
                        .find(link)
        if (m != null) return m.groupValues[1]
        val old = Regex("""mega\.nz/#!([A-Za-z0-9_-]{6,})""").find(link)
        if (old != null) return old.groupValues[1]
        return null
    }

    /**
     * Cek file mega masih ada atau tidak via API publik.
     * Kalau API tidak bisa dihubungi (network error / timeout), kembalikan
     * TRUE: lebih baik link tetap muncul (perilaku lama) daripada membuang
     * file yang mungkin hidup karena mega gagal diakses sementara.
     */
    suspend fun isFileAlive(link: String): Boolean {
        val handle = fileHandle(link) ?: return true
        val id = seq++ // counter monoton; id mega cuma untuk multiplexing client
        return try {
            val res =
                    app.post(
                            url = "$API$id",
                            // body: [{"a":"g","p":"<handle>"}]
                            data = null, // (data) non-null menimpa (json) di NiceHttp
                            json = listOf(mapOf("a" to "g", "p" to handle)),
                            headers = mapOf("User-Agent" to BROWSER_UA),
                            timeout = 10)
            val body = res.text.trim()
            // sukses: [ { "g": "...", "s": ..., "at": ... } ]
            // gagal : [ -9 ] / [ -11 ] / dst.
            body.startsWith("[") && body.substringAfter("[", "").trimStart().startsWith("{")
        } catch (e: Throwable) {
            true
        }
    }
}
