package com.oploverz

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OploverzPlugin : Plugin() {
    override fun load(context: Context) {
        // Register all shared StreamWish-family extractors that might handle
        // upbolt.to (or any other Cloudflare-protected source). Registering
        // them makes them available to loadExtractor() in MainAPI.loadLinks().
        registerMainAPI(Oploverz())

        registerExtractorAPI(UpBolt())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Short())
        registerExtractorAPI(Shorticu())
        registerExtractorAPI(Ghbrisk())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(Hgcloud())
        registerExtractorAPI(Luluvdo())
    }
}