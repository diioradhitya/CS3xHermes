package com.layarasia

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LayarAsiaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LayarAsia())

        // Scanned the seven home sections: the site only ever hands out two
        // embed families, both Byse-backed.
        //   bysesukior.com / bysetayico.com  -> handled here
        //   layarasia.upns.live              -> no extractor yet
        registerExtractorAPI(ByseExtractor())
    }
}