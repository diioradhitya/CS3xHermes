package com.loklok

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LoklokPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LoklokProvider())
    }
}
