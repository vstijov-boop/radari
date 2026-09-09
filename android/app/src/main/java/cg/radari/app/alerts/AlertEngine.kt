package cg.radari.app.alerts

import cg.radari.app.model.Threat
import cg.radari.app.model.ThreatType
import cg.radari.app.geo.Geo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Jedno mjerenje polozaja vozila — ulaz u engine. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMs: Double,
    /** Azimut kretanja u stepenima; null dok se smjer jos ne zna. */
    val heading: Double?,
)

/** Vrsta zvucnog dojava koji engine zatrazuje. */
enum class Cue {
    /** Ulazak u zonu radara ispred (2x niski beep + govor). */
    APPROACH,
    /** Prilaz blizini radara (<180 m, 3x visoki beep). */
    CLOSE,
    /** Ulaz u sekcijsku dionicu. */
    SECTION_START,
    /** Prosjek iznad ogranicenja na dionici. */
    SECTION_OVER,
    /** Prosjek se vratio ispod ogranicenja. */
    SECTION_OK,
    /** Kraj dionice s rezimeom. */
    SECTION_DONE,
}

/**
 * Dojav s podacima koji su vazili u trenutku okidanja.
 * Bitno: [threat] je bas ona lokacija koja je okinula dojav — ne "najbliza",
 * jer u istom fiksu moze u zonu uci vise lokacija.
 */
data class CueEvent(
    val kind: Cue,
    val threat: Threat? = null,
    val distanceM: Double = 0.0,
    val limitKmh: Int = 0,
    val avgKmh: Double = 0.0,
)

/** Rezultat obrade jednog GPS fiks-a. */
data class AlertTick(
    val cues: List<CueEvent> = emptyList(),
    /** Najbliza pretnja ispred, za UI i Auto ekran. */
    val nearest: Nearest? = null,
    /** Prosjecna brzina na aktivnoj dionici (km/h), ako je dionica aktivna. */
    val sectionAvgKmh: Double? = null,
    val sectionLimitKmh: Int? = null,
    /** Preostalo metara do izlaza iz dionice. */
    val sectionLeftM: Double? = null,
)

data class Nearest(
    val threat: Threat,
    val distanceM: Double,
    val bearingDeg: Double,
    /** true ako je u konusu ispred (ili blize od 200 m). */
    val ahead: Boolean,
    val limitKmh: Int,
)

/**
 * Cista logika bez Android zavisnosti — 1:1 preslikana iz onFix()/section() u PWA:
 *  lead = clamp(speed*20, 350, 1200)
 *  ispred = ugao < 55 stepeni ILI udaljenost < 200 m
 *  stanja radara: 0 -> 1 na lead, 1 -> 2 na 180 m, reset na lead*1.6 ili skretanje > 110
 */
class AlertEngine(
    private val threats: () -> List<Threat>,
    private val limitOf: (Threat) -> Int,
    /** Rucno podesena duzina dionice u metrima po id-u ulazne tacke; 0 = racunaj iz koordinata. */
    private val sectionLengthM: (Long) -> Double,
) {
    private val st = HashMap<Long, Int>()
    private var active: Section? = null
    private var lastFix: Fix? = null

    /** Par tacaka sekcijskog mjerenja, isto parovanje kao SEC u PWA. */
    data class SectionPair(val a: Threat, val b: Threat, val baseLengthM: Double)

    private data class Section(
        val pair: SectionPair,
        val endLat: Double,
        val endLon: Double,
        val limitKmh: Int,
        val lengthM: Double,
        val t0Ms: Long,
        var travelled: Double = 0.0,
        var warned: Boolean = false,
    )

    fun onFix(fix: Fix, nowMs: Long): AlertTick {
        val lead = min(1200.0, max(350.0, fix.speedMs * 20.0))
        val cues = ArrayList<CueEvent>(2)
        val near = ArrayList<Pair<Threat, Double>>(8)

        for (r in threats()) {
            val d = Geo.dist(fix.lat, fix.lon, r.lat, r.lon)
            if (d > 6000) { st[r.id] = 0; continue }
            val b = Geo.bearing(fix.lat, fix.lon, r.lat, r.lon)
            val off = fix.heading?.let { Geo.dAng(it, b) } ?: 0.0
            val ahead = fix.heading == null || off < 55.0 || d < 200.0

            // Stanje se azurira u toku provjere (kao r.st u PWA), pa oba praga
            // mogu okinuti u istom fiksu ako se radaru pridje naglo.
            var s = st[r.id] ?: 0
            if (ahead && d <= lead && s == 0) {
                s = 1
                cues += CueEvent(Cue.APPROACH, r, d, limitOf(r))
            }
            if (ahead && d <= 180.0 && s == 1) {
                s = 2
                cues += CueEvent(Cue.CLOSE, r, d, limitOf(r))
            }
            if (d > lead * 1.6 || (fix.heading != null && off > 110.0 && d > 250.0)) {
                s = 0
            }
            st[r.id] = s

            if (ahead) near += r to d
        }

        near.sortBy { it.second }
        val nearest = near.firstOrNull()?.let { (r, d) ->
            Nearest(
                threat = r,
                distanceM = d,
                bearingDeg = Geo.bearing(fix.lat, fix.lon, r.lat, r.lon),
                ahead = true,
                limitKmh = limitOf(r),
            )
        }

        val sec = tickSection(fix, nowMs, cues)
        lastFix = fix

        return AlertTick(
            cues = cues,
            nearest = nearest,
            sectionAvgKmh = sec?.avgKmh,
            sectionLimitKmh = sec?.limitKmh,
            sectionLeftM = sec?.leftM,
        )
    }

    private data class SectionState(val avgKmh: Double, val limitKmh: Int, val leftM: Double)

    /** Sekcijsko mjerenje — port section() iz PWA. */
    private fun tickSection(fix: Fix, nowMs: Long, cues: MutableList<CueEvent>): SectionState? {
        val a = active
        if (a != null) {
            lastFix?.let { a.travelled += Geo.dist(it.lat, it.lon, fix.lat, fix.lon) }

            val dEnd = Geo.dist(fix.lat, fix.lon, a.endLat, a.endLon)
            val elapsed = (nowMs - a.t0Ms) / 1000.0
            val done = (a.lengthM - dEnd).coerceIn(0.0, a.lengthM)
            val avg = if (elapsed > 3.0) max(a.travelled, done) / elapsed * 3.6 else 0.0
            val left = max(0.0, a.lengthM - max(a.travelled, done))

            if (avg > a.limitKmh && !a.warned && elapsed > 20) {
                a.warned = true
                cues += CueEvent(Cue.SECTION_OVER, a.pair.a, left, a.limitKmh, avg)
            }
            if (avg <= a.limitKmh && a.warned && elapsed > 20) {
                a.warned = false
                cues += CueEvent(Cue.SECTION_OK, a.pair.a, left, a.limitKmh, avg)
            }
            if (dEnd < 120 || a.travelled > a.lengthM * 1.8) {
                cues += CueEvent(Cue.SECTION_DONE, a.pair.a, left, a.limitKmh, avg)
                active = null
            }
            return SectionState(avg, a.limitKmh, left)
        }

        // Ulaz u novu dionicu: <150 m od tacke A ili B, uz smjer ka drugoj tacki <70 stepeni.
        for (s in sectionPairs()) {
            for ((p, q) in listOf(s.a to s.b, s.b to s.a)) {
                val dp = Geo.dist(fix.lat, fix.lon, p.lat, p.lon)
                if (dp >= 150.0) continue
                val toQ = Geo.bearing(fix.lat, fix.lon, q.lat, q.lon)
                if (fix.heading != null && Geo.dAng(fix.heading, toQ) >= 70.0) continue
                val lim = min(limitOf(s.a), limitOf(s.b))
                active = Section(
                    pair = s,
                    endLat = q.lat, endLon = q.lon,
                    limitKmh = lim,
                    lengthM = sectionLengthM(s.a.id).takeIf { it > 0 } ?: s.baseLengthM,
                    t0Ms = nowMs,
                )
                cues += CueEvent(Cue.SECTION_START, p, dp, lim)
                return null
            }
        }
        return null
    }

    /**
     * Parovi dionica: kao u PWA, tacka tipa 3 se pari sa SLJEDECOM lokacijom u bazi
     * ako je tipa 4 — redoslijed u JSON-u je isti kao redoslijed u PWA nizu.
     * Kesira se dok se broj lokacija ne promijeni (patrole ekipe dolaze i odlaze).
     */
    private var pairsCache: List<SectionPair> = emptyList()
    private var pairsCacheKey: Int = -1

    fun sectionPairs(): List<SectionPair> {
        val list = threats().filter { it.type == ThreatType.SECTION_A || it.type == ThreatType.SECTION_B }
        val key = list.size * 31 + list.firstOrNull()?.id?.toInt().hashCode()
        if (key == pairsCacheKey) return pairsCache
        val out = ArrayList<SectionPair>()
        for (i in 0 until list.size - 1) {
            val a = list[i]
            val b = list[i + 1]
            if (a.type == ThreatType.SECTION_A && b.type == ThreatType.SECTION_B) {
                val base = Math.round(Geo.dist(a.lat, a.lon, b.lat, b.lon) * 1.05 / 10.0) * 10.0
                out += SectionPair(a, b, base)
            }
        }
        pairsCache = out
        pairsCacheKey = key
        return out
    }

    fun reset() {
        st.clear()
        active = null
        lastFix = null
    }
}

/** Zaokruzivanje udaljenosti na 100 m za govor — kao u PWA. */
fun Double.roundToHundred(): Int = (this / 100.0).roundToInt() * 100
