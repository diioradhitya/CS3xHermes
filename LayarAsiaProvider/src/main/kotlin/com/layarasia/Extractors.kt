package com.layarasia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

// UPNShare Extractor
class UPNShare : ExtractorApi() {
    override val name = "UPNShare"
    override val mainUrl = "https://upnshare.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val document = response.document

        val script = document.selectFirst("script:contains(sources), script:contains(file:), script:contains(src:)")?.data()
            ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { match ->
            generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = "$mainUrl/",
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
        }

        Regex(":\\s*\"(.*?\\.mp4.*?)\"").findAll(script).forEach { match ->
            val videoUrl = fixUrl(match.groupValues[1])
            callback(newExtractorLink(name, name, videoUrl) {
                this.referer = mainUrl
                this.headers = mapOf("Referer" to mainUrl)
            })
        }
    }
}

// HydraX Extractor
class HydraX : ExtractorApi() {
    override val name = "HydraX"
    override val mainUrl = "https://hydrax.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val document = response.document

        val script = document.selectFirst("script:contains(sources), script:contains(file:), script:contains(src:)")?.data()
            ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { match ->
            generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = "$mainUrl/",
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
        }

        Regex(":\\s*\"(.*?\\.mp4.*?)\"").findAll(script).forEach { match ->
            val videoUrl = fixUrl(match.groupValues[1])
            callback(newExtractorLink(name, name, videoUrl) {
                this.referer = mainUrl
                this.headers = mapOf("Referer" to mainUrl)
            })
        }
    }
}

// StreamP2P Extractor
class StreamP2P : ExtractorApi() {
    override val name = "StreamP2P"
    override val mainUrl = "https://streamp2p.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val document = response.document

        val script = document.selectFirst("script:contains(sources), script:contains(file:), script:contains(src:)")?.data()
            ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { match ->
            generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = "$mainUrl/",
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
        }

        Regex(":\\s*\"(.*?\\.mp4.*?)\"").findAll(script).forEach { match ->
            val videoUrl = fixUrl(match.groupValues[1])
            callback(newExtractorLink(name, name, videoUrl) {
                this.referer = mainUrl
                this.headers = mapOf("Referer" to mainUrl)
            })
        }
    }
}

// Terabox Extractor (for download links)
class Terabox : ExtractorApi() {
    override val name = "Terabox"
    override val mainUrl = "https://terabox.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalReferer = referer ?: mainUrl
        callback(newExtractorLink(name, name, url) {
            this.referer = finalReferer
            this.headers = mapOf("Referer" to finalReferer)
        })
    }
}

// FileMoon Extractor
class FileMoon : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val document = response.document

        val script = document.selectFirst("script:contains(sources), script:contains(file:), script:contains(src:)")?.data()
            ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { match ->
            generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = "$mainUrl/",
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
        }

        Regex(":\\s*\"(.*?\\.mp4.*?)\"").findAll(script).forEach { match ->
            val videoUrl = fixUrl(match.groupValues[1])
            callback(newExtractorLink(name, name, videoUrl) {
                this.referer = mainUrl
                this.headers = mapOf("Referer" to mainUrl)
            })
        }
    }
}

// Fileditch Extractor
class Fileditch : ExtractorApi() {
    override val name = "Fileditch"
    override val mainUrl = "https://fileditch.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val document = response.document

        val script = document.selectFirst("script:contains(sources), script:contains(file:), script:contains(src:)")?.data()
            ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { match ->
            generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = "$mainUrl/",
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
        }

        Regex(":\\s*\"(.*?\\.mp4.*?)\"").findAll(script).forEach { match ->
            val videoUrl = fixUrl(match.groupValues[1])
            callback(newExtractorLink(name, name, videoUrl) {
                this.referer = mainUrl
                this.headers = mapOf("Referer" to mainUrl)
            })
        }
    }
}

// Mirrored Extractor (generic for mirrored links)
class Mirrored : ExtractorApi() {
    override val name = "Mirrored"
    override val mainUrl = "https://mirrored.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val document = response.document

        val script = document.selectFirst("script:contains(sources), script:contains(file:), script:contains(src:)")?.data()
            ?: return

        val finalReferer = referer ?: mainUrl

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { match ->
            generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = finalReferer,
                headers = mapOf("Referer" to finalReferer)
            ).forEach(callback)
        }

        Regex(":\\s*\"(.*?\\.mp4.*?)\"").findAll(script).forEach { match ->
            val videoUrl = fixUrl(match.groupValues[1])
            callback(newExtractorLink(name, name, videoUrl) {
                this.referer = finalReferer
                this.headers = mapOf("Referer" to finalReferer)
            })
        }
    }
}