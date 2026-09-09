package cg.radari.app

import android.app.Application
import com.mapbox.common.MapboxOptions

class RadariApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Mapbox v11: jedan globalni token po procesu.
        // Token dolazi iz local.properties -> BuildConfig.
        if (BuildConfig.MAPBOX_PUBLIC_TOKEN.isNotBlank()) {
            MapboxOptions.accessToken = BuildConfig.MAPBOX_PUBLIC_TOKEN
        }
    }
}
