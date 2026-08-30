package com.oploverz

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OploverzPlugin : Plugin() {
    override fun load(context: Context) {
        // UpBolt class in Extractors.kt extends StreamWishExtractor and is
        // auto-registered by CloudStream's reflection. We just need to
        // register the MainAPI.
        registerMainAPI(Oploverz())
    }
}