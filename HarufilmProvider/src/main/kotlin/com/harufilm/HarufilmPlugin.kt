package com.harufilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HarufilmPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HarufilmProvider())

        // Custom HaruStream + HaruPlayer extractors (in HaruExtractors.kt —
        // separate file so release.sh doesn"t overwrite them)
        registerExtractorAPI(HaruStreamExtractor())
        registerExtractorAPI(HaruStreamEuCcExtractor())
        registerExtractorAPI(HaruPlayerExtractor())
    }
}
