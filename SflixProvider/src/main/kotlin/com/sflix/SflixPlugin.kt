package com.sflix

import android.content.Context
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SflixProvider())

        // 6 Sflix mirror hosts
        registerExtractorAPI(MoviesapiExtractor())
        registerExtractorAPI(VidcoreExtractor())
        registerExtractorAPI(VideasyExtractor())
        registerExtractorAPI(VidfastExtractor())
        registerExtractorAPI(VidsrcEmbedExtractor())
        registerExtractorAPI(EmbedmasterExtractor())

        // CloudStream built-ins (fallback video hosts)
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(VidStack())
    }
}
