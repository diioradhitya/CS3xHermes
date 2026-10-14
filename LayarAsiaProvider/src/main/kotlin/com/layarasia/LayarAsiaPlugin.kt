package com.layarasia

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LayarAsiaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LayarAsia())

        registerExtractorAPI(UPNShare())
        registerExtractorAPI(HydraX())
        registerExtractorAPI(StreamP2P())
        registerExtractorAPI(Terabox())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(Fileditch())
        registerExtractorAPI(Mirrored())
    }
}