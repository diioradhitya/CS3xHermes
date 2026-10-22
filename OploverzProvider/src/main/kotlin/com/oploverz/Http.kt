package com.oploverz

/**
 * User-Agent browser. Tanpa ini, WAF situs (Cloudflare) menolak request
 * dari client HTTP bawaan (User-Agent "okhttp/..."), dan halaman yang
 * sebenarnya tersedia dianggap gagal diakses.
 */
internal const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
