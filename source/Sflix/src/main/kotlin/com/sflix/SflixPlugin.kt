package com.sflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Sflix())
    }
}