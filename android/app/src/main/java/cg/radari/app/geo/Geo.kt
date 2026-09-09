package cg.radari.app.geo

import kotlin.math.*

object Geo {
    const val EARTH_R = 6371000.0
    private val RAD = Math.PI / 180.0

    /** Haversine udaljenost u metrima — ista formula kao dist() u PWA. */
    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * RAD
        val p2 = lat2 * RAD
        val dp = (lat2 - lat1) * RAD
        val dl = (lon2 - lon1) * RAD
        val x = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_R * asin(sqrt(x))
    }

    /** Azimut od tacke A ka tacki B, 0..360 stepeni — kao bearing() u PWA. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * RAD
        val p2 = lat2 * RAD
        val dl = (lon2 - lon1) * RAD
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((atan2(y, x) / RAD) + 360.0) % 360.0
    }

    /** Najmanja razlika dva azimuta u stepenima (0..180) — kao dAng() u PWA. */
    fun dAng(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
