package cg.radari.app.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.Style
import com.mapbox.maps.extension.androidauto.MapboxCarMap
import com.mapbox.maps.extension.androidauto.mapboxMapInstaller

/**
 * Jedan Session za glavni displej. MapboxCarMap se instalira ovdje, a Screen
 * registruje observer koji ucitava stil, markere i konus kad dodje surface.
 * Ako Mapbox nema token, surface jednostavno ne stigne — ekran i dalje radi
 * (NavigationTemplate s ikonom i udaljenoscu), samo bez mape ispod.
 */
class RadariSession : Session() {

    val mapboxCarMap: MapboxCarMap = mapboxMapInstaller()
        .install { carContext ->
            MapInitOptions(context = carContext, styleUri = Style.DARK)
        }

    override fun onCreateScreen(intent: Intent): Screen = RadariMapScreen(carContext, mapboxCarMap)
}
