package com.germanstreams

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmoPlugin : Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        registerMainAPI(FilmoProvider())
    }
}
