package cg.radari.app.model

/**
 * Vrsta lokacije — isti brojevi kao u PWA bazi:
 * 1 = kamera na raskrsnici, 2 = stacionarni radar,
 * 3 = sekcijsko mjerenje ulaz, 4 = izlaz, 5 = patrola ekipe (dinamicka).
 */
enum class ThreatType(val id: Int, val defaultLimit: Int) {
    INTERSECTION(1, 50),
    RADAR(2, 60),
    SECTION_A(3, 80),
    SECTION_B(4, 80),
    PATROL(5, 0);

    companion object {
        /** null za nepoznat tip — neispravan unos u bazi ne smije da srusi ucitavanje. */
        fun fromId(id: Int): ThreatType? = entries.firstOrNull { it.id == id }
    }
}

data class Threat(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val type: ThreatType,
    val city: String = "",
    val name: String = "",
    /** true = dinamicka patrola ekipe, ide van zvanicne baze i istice s vremenom. */
    val team: Boolean = false,
    /** UTC ms kad je patrola prijavljena (samo za team=true). */
    val createdAt: Long = 0L,
)
