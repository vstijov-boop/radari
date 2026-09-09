package cg.radari.app.alerts

import cg.radari.app.geo.Geo
import cg.radari.app.model.Threat
import cg.radari.app.model.ThreatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logika je namjerno bez Android zavisnosti, pa se cijeli scenario prilaza radaru
 * moze odvrtjeti kao obicni JVM test.
 */
class AlertEngineTest {

    private val radar = Threat(
        id = 1, lat = 42.5, lon = 19.3, type = ThreatType.RADAR, city = "Test", name = "test radar",
    )

    private fun engine(list: List<Threat> = listOf(radar)) = AlertEngine(
        threats = { list },
        limitOf = { it.type.defaultLimit },
        sectionLengthM = { 0.0 },
    )

    /** Tacka na `m` metara juzno od radara — vozilo vozi ka sjeveru, radar je ispred. */
    private fun southOf(m: Double) = Pair(radar.lat - m / 111320.0, radar.lon)

    private fun fixAt(m: Double, speedMs: Double = 25.0): Fix {
        val (lat, lon) = southOf(m)
        return Fix(lat, lon, speedMs, heading = 0.0)
    }

    @Test
    fun `ulazak u zonu javlja jednom`() {
        val e = engine()
        var t = 1_000L
        // 25 m/s -> lead = 500 m; na 900 m jos nema dojava.
        assertTrue(e.onFix(fixAt(900.0), t).cues.isEmpty())
        t += 1000
        val entering = e.onFix(fixAt(480.0), t).cues
        assertEquals(listOf(Cue.APPROACH), entering.map { it.kind })
        t += 1000
        assertTrue("dojav se ne smije ponavljati", e.onFix(fixAt(400.0), t).cues.isEmpty())
    }

    @Test
    fun `blizi prag javlja tacno jednom`() {
        val e = engine()
        var t = 1_000L
        e.onFix(fixAt(480.0), t)
        t += 1000
        assertEquals(listOf(Cue.CLOSE), e.onFix(fixAt(170.0), t).cues.map { it.kind })
        // Ovo je bila greska: uslov `st >= 1` je pustao beep u svakom sljedecem fiksu.
        for (d in listOf(150.0, 120.0, 90.0, 60.0)) {
            t += 1000
            assertTrue("CLOSE se ponavlja na $d m", e.onFix(fixAt(d), t).cues.isEmpty())
        }
    }

    @Test
    fun `dojav nosi lokaciju koja ga je okinula`() {
        // 55 m ispred radara, dakle i on unutar dometa od 500 m.
        val drugi = radar.copy(id = 2, lat = radar.lat - 0.0005, type = ThreatType.INTERSECTION)
        val e = engine(listOf(radar, drugi))
        val cues = e.onFix(fixAt(480.0), 1_000L).cues
        assertEquals(2, cues.size)
        assertEquals(setOf(1L, 2L), cues.mapNotNull { it.threat?.id }.toSet())
        // Kamera na raskrsnici i radar nemaju isto ogranicenje — poruke se ne smiju pobrkati.
        assertEquals(
            ThreatType.RADAR.defaultLimit,
            cues.first { it.threat?.id == 1L }.limitKmh,
        )
        assertEquals(
            ThreatType.INTERSECTION.defaultLimit,
            cues.first { it.threat?.id == 2L }.limitKmh,
        )
    }

    @Test
    fun `udaljavanje resetuje stanje pa novi prolaz opet javlja`() {
        val e = engine()
        var t = 1_000L
        e.onFix(fixAt(480.0), t)
        t += 1000
        // 900 m > lead*1.6 (800 m) -> reset
        e.onFix(fixAt(900.0), t)
        t += 1000
        assertEquals(listOf(Cue.APPROACH), e.onFix(fixAt(480.0), t).cues.map { it.kind })
    }

    @Test
    fun `radar iza ledja ne javlja`() {
        val e = engine()
        // Vozilo je sjeverno od radara i vozi dalje na sjever: radar je iza, ugao 180.
        val lat = radar.lat + 400.0 / 111320.0
        val cues = e.onFix(Fix(lat, radar.lon, 25.0, heading = 0.0), 1_000L).cues
        assertTrue(cues.isEmpty())
    }

    @Test
    fun `sekcijsko mjerenje se pari po redoslijedu u bazi`() {
        val a = Threat(id = 7, lat = 42.40, lon = 19.30, type = ThreatType.SECTION_A, city = "T")
        val b = Threat(id = 8, lat = 42.45, lon = 19.30, type = ThreatType.SECTION_B, city = "T")
        val e = engine(listOf(a, b))
        val pairs = e.sectionPairs()
        assertEquals(1, pairs.size)
        assertEquals(7L, pairs[0].a.id)
        val direct = Geo.dist(a.lat, a.lon, b.lat, b.lon)
        assertEquals(direct * 1.05, pairs[0].baseLengthM, 10.0)

        // Ulaz kod tacke A, smjer ka B -> dojav o pocetku dionice.
        val cues = e.onFix(Fix(a.lat - 0.0005, a.lon, 25.0, heading = 0.0), 1_000L).cues
        assertTrue(cues.any { it.kind == Cue.SECTION_START })
        assertEquals(80, cues.first { it.kind == Cue.SECTION_START }.limitKmh)
    }
}
