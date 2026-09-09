package cg.radari.app.auto

import android.content.Intent
import android.graphics.Bitmap
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cg.radari.app.R
import cg.radari.app.alerts.Nearest
import cg.radari.app.geo.Geo
import cg.radari.app.alerts.TrackingService
import cg.radari.app.model.ThreatRepository
import cg.radari.app.model.ThreatType
import cg.radari.app.team.TeamRepo
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.Style
import com.mapbox.maps.extension.androidauto.MapboxCarMap
import com.mapbox.maps.extension.androidauto.MapboxCarMapObserver
import com.mapbox.maps.extension.androidauto.MapboxCarMapSurface
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.generated.fillLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Glavni ekran na Android Auto displeju.
 *
 * Uvijek se koristi NavigationTemplate: on je jedini template koji host dozvoljava
 * da se osvjezava po volji (ostali se broje u kvotu i app bude ugasena). Kad Mapbox
 * povrsina postoji, ispod template-a se crta mapa; kad je nema, ostaje isti ekran
 * s ikonom vrste pretnje i udaljenoscu koja se osvjezava.
 */
class RadariMapScreen(
    carContext: CarContext,
    private val mapboxCarMap: MapboxCarMap,
) : Screen(carContext) {

    private var nearest: Nearest? = null
    private var surface: MapboxCarMapSurface? = null
    private var radarSource: GeoJsonSource? = null
    private var coneSource: GeoJsonSource? = null
    private var vehicleSource: GeoJsonSource? = null

    /** Zadnji prikazani tekst — template se osvjezava samo kad se stvarno promijeni. */
    private var lastShown: String? = null

    private val observer = object : MapboxCarMapObserver {
        override fun onAttached(mapboxCarMapSurface: MapboxCarMapSurface) {
            surface = mapboxCarMapSurface
            val map = mapboxCarMapSurface.mapSurface.mapboxMap
            map.loadStyle(Style.DARK) { style ->
                addImage(style, ICON_INTERSECTION, 0xFF4A90D9.toInt())
                addImage(style, ICON_RADAR, 0xFFE8352A.toInt())
                addImage(style, ICON_SECTION, 0xFF39C07A.toInt())
                addImage(style, ICON_PATROL, 0xFFF2B21C.toInt())
                addImage(style, ICON_VEHICLE, 0xFFFFFFFF.toInt())

                coneSource = geoJsonSource(SRC_CONE) { featureCollection(EMPTY) }.also { style.addSource(it) }
                radarSource = geoJsonSource(SRC_RADARS) { featureCollection(EMPTY) }.also { style.addSource(it) }
                vehicleSource = geoJsonSource(SRC_VEHICLE) { featureCollection(EMPTY) }.also { style.addSource(it) }

                // Konus ide prvi da markeri ostanu iznad njega.
                style.addLayer(
                    fillLayer(LYR_CONE, SRC_CONE) {
                        fillColor("#4a90d9")
                        fillOpacity(0.18)
                    },
                )
                style.addLayer(
                    symbolLayer(LYR_RADARS, SRC_RADARS) {
                        iconImage(Expression.get(PROP_KIND))
                        iconAllowOverlap(true)
                        iconIgnorePlacement(true)
                    },
                )
                style.addLayer(
                    symbolLayer(LYR_VEHICLE, SRC_VEHICLE) {
                        iconImage(ICON_VEHICLE)
                        iconAllowOverlap(true)
                    },
                )
                redrawMap()
            }
        }

        override fun onDetached(mapboxCarMapSurface: MapboxCarMapSurface) {
            surface = null
            radarSource = null
            coneSource = null
            vehicleSource = null
            invalidate()
        }
    }

    init {
        mapboxCarMap.registerObserver(observer)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                mapboxCarMap.unregisterObserver(observer)
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingService.nearest.collect { n ->
                    nearest = n
                    redrawMap()
                    val text = templateText(n)
                    if (text != lastShown) {
                        lastShown = text
                        invalidate()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingService.running.collect { invalidate() }
            }
        }
    }

    // ---- mapa ----

    private fun addImage(style: Style, name: String, tint: Int) {
        val d = ContextCompat.getDrawable(carContext, R.drawable.ic_stat_radar)?.mutate() ?: return
        d.setTint(tint)
        val bmp: Bitmap = d.toBitmap(56, 56)
        style.addImage(name, bmp)
    }

    private fun mapIcon(type: ThreatType): String = when (type) {
        ThreatType.INTERSECTION -> ICON_INTERSECTION
        ThreatType.RADAR -> ICON_RADAR
        ThreatType.SECTION_A, ThreatType.SECTION_B -> ICON_SECTION
        ThreatType.PATROL -> ICON_PATROL
    }

    private fun redrawMap() {
        val fix = TrackingService.lastVehicleFix.value ?: return

        // Konus ispred vozila: isti ugao (55) i domet (speed*20, 350-1200 m) kao u PWA.
        coneSource?.let { src ->
            val heading = fix.heading
            if (heading == null) {
                src.featureCollection(EMPTY)
            } else {
                val lead = (fix.speedMs * 20.0).coerceIn(350.0, 1200.0)
                val ring = ArrayList<Point>()
                ring += Point.fromLngLat(fix.lon, fix.lat)
                var a = -55.0
                while (a <= 55.0) {
                    ring += offset(fix.lat, fix.lon, heading + a, lead)
                    a += 5.0
                }
                ring += Point.fromLngLat(fix.lon, fix.lat) // zatvoren prsten
                src.featureCollection(
                    FeatureCollection.fromFeature(
                        Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))),
                    ),
                )
            }
        }

        radarSource?.let { src ->
            val feats = ArrayList<Feature>()
            for (t in ThreatRepository.get(carContext.applicationContext).all()) {
                if (Geo.dist(fix.lat, fix.lon, t.lat, t.lon) > 6000) continue
                val f = Feature.fromGeometry(Point.fromLngLat(t.lon, t.lat))
                f.addStringProperty(PROP_KIND, mapIcon(t.type))
                feats += f
            }
            src.featureCollection(FeatureCollection.fromFeatures(feats))
        }

        vehicleSource?.featureCollection(
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat))),
        )

        // Kamera prati vozilo; bez ovoga mapa ostane na pocetnoj poziciji stila.
        surface?.mapSurface?.mapboxMap?.setCamera(
            CameraOptions.Builder()
                .center(Point.fromLngLat(fix.lon, fix.lat))
                .zoom(if (fix.speedMs * 3.6 > 80) 14.0 else 15.5)
                .bearing(fix.heading ?: 0.0)
                .pitch(45.0)
                .build(),
        )
    }

    /** Tacka na `distM` metara u smjeru `bearingDeg` od zadate pozicije. */
    private fun offset(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Point {
        val rad = Math.toRadians(bearingDeg)
        val dLat = distM * cos(rad) / 111320.0
        val dLon = distM * sin(rad) / (111320.0 * cos(Math.toRadians(lat)))
        return Point.fromLngLat(lon + dLon, lat + dLat)
    }

    // ---- template ----

    override fun onGetTemplate(): Template {
        val n = nearest
        val title = templateText(n)
        val icon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_stat_radar),
        ).setTint(tintOf(n?.threat?.type)).build()

        val info = MessageInfo.Builder(title).setImage(icon)
        if (n != null) info.setText(subtitle(n))

        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(
                                carContext.getString(
                                    if (TrackingService.running.value) R.string.tracking_stop
                                    else R.string.tracking_start,
                                ),
                            )
                            .setOnClickListener { toggleTracking() }
                            .build(),
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.patrol_here))
                            .setOnClickListener { reportPatrol() }
                            .build(),
                    )
                    .build(),
            )
            .setNavigationInfo(info.build())
            .build()
    }

    private fun templateText(n: Nearest?): String = when {
        n != null -> fmtDist(n.distanceM)
        TrackingService.running.value -> carContext.getString(R.string.no_threat)
        else -> carContext.getString(R.string.tracking_off)
    }

    private fun subtitle(n: Nearest): String {
        val kind = kindName(n.threat.type)
        return if (n.limitKmh > 0) "$kind · ${n.limitKmh} km/h" else kind
    }

    private fun tintOf(type: ThreatType?): CarColor = when (type) {
        ThreatType.INTERSECTION -> CarColor.BLUE
        ThreatType.RADAR -> CarColor.RED
        ThreatType.SECTION_A, ThreatType.SECTION_B -> CarColor.GREEN
        ThreatType.PATROL -> CarColor.YELLOW
        null -> CarColor.DEFAULT
    }

    // ---- akcije s auta ----

    private fun toggleTracking() {
        val intent = Intent(carContext, TrackingService::class.java)
        if (TrackingService.running.value) {
            carContext.startService(intent.setAction(TrackingService.ACTION_STOP))
        } else {
            ContextCompat.startForegroundService(carContext, intent)
        }
        invalidate()
    }

    private fun reportPatrol() {
        val fix = TrackingService.lastVehicleFix.value
        if (fix == null) {
            CarToast.makeText(carContext, R.string.no_gps, CarToast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val res = runCatching {
                TeamRepo.reportPatrol(carContext.applicationContext, fix.lat, fix.lon, "patrola")
            }.getOrNull()
            val msg = when (res) {
                TeamRepo.RESULT_SENT -> R.string.patrol_sent
                TeamRepo.RESULT_QUEUED -> R.string.patrol_queue
                else -> R.string.patrol_off
            }
            CarToast.makeText(carContext, msg, CarToast.LENGTH_SHORT).show()
        }
    }

    private fun kindName(type: ThreatType): String = carContext.getString(
        when (type) {
            ThreatType.INTERSECTION -> R.string.threat_intersection
            ThreatType.RADAR -> R.string.threat_radar
            ThreatType.SECTION_A -> R.string.threat_section_start
            ThreatType.SECTION_B -> R.string.threat_section_end
            ThreatType.PATROL -> R.string.threat_patrol
        },
    )

    private fun fmtDist(m: Double): String =
        if (m < 950) "${(m / 10).toInt() * 10} m"
        else String.format(Locale.US, "%.1f km", m / 1000.0)

    private companion object {
        const val SRC_RADARS = "radari-radars"
        const val SRC_CONE = "radari-cone"
        const val SRC_VEHICLE = "radari-vehicle"
        const val LYR_RADARS = "radari-radars-layer"
        const val LYR_CONE = "radari-cone-layer"
        const val LYR_VEHICLE = "radari-vehicle-layer"
        const val PROP_KIND = "kind"

        const val ICON_INTERSECTION = "ic-intersection"
        const val ICON_RADAR = "ic-radar"
        const val ICON_SECTION = "ic-section"
        const val ICON_PATROL = "ic-patrol"
        const val ICON_VEHICLE = "ic-vehicle"

        val EMPTY: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    }
}
