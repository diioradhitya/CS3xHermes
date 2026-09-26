package com.idnmovie

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IdnMoviePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IdnMovieProvider())
    }
}
