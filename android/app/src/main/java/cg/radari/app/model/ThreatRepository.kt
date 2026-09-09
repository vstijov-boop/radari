package cg.radari.app.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jedan izvor istine o lokacijama za cijelu aplikaciju (telefon i Auto).
 * Zvanicna baza se cita jednom iz res/raw; patrole ekipe zive odvojeno i
 * postavljaju se izvana (sync servis). Ogranicenja po lokaciji se cuvaju ovdje,
 * u istom obliku kao CFG.lim u PWA, pa se mogu prenijeti izvozom/uvozom.
 */
class ThreatRepository private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val staticThreats: List<Threat> = loadStatic(context)

    private val _team = MutableStateFlow<List<Threat>>(emptyList())
    val team: StateFlow<List<Threat>> = _team.asStateFlow()

    /** Ogranicenja po id-u lokacije (override preko podrazumijevanog za tip). */
    private val limits = HashMap<Long, Int>().apply {
        runCatching {
            val o = JSONObject(prefs.getString(KEY_LIMITS, "{}") ?: "{}")
            o.keys().forEach { k -> put(k.toLong(), o.getInt(k)) }
        }
    }

    /** Rucno podesene duzine dionica u metrima, po id-u ulazne tacke. */
    private val lengths = HashMap<Long, Double>().apply {
        runCatching {
            val o = JSONObject(prefs.getString(KEY_LENGTHS, "{}") ?: "{}")
            o.keys().forEach { k -> put(k.toLong(), o.getDouble(k)) }
        }
    }

    /** Sve aktivne lokacije: staticna baza + zive patrole ekipe. */
    fun all(): List<Threat> = staticThreats + _team.value

    fun staticList(): List<Threat> = staticThreats

    fun setTeam(list: List<Threat>) {
        _team.value = list
    }

    fun limitOf(t: Threat): Int = limits[t.id] ?: t.type.defaultLimit

    fun setLimit(id: Long, kmh: Int?) {
        if (kmh == null || kmh <= 0) limits.remove(id) else limits[id] = kmh
        persist(KEY_LIMITS, limits.mapValues { it.value as Number })
    }

    /** 0 = nije rucno podeseno, racuna se iz koordinata. */
    fun lengthOf(sectionStartId: Long): Double = lengths[sectionStartId] ?: 0.0

    fun setLength(sectionStartId: Long, meters: Double?) {
        if (meters == null || meters <= 0) lengths.remove(sectionStartId) else lengths[sectionStartId] = meters
        persist(KEY_LENGTHS, lengths.mapValues { it.value as Number })
    }

    private fun persist(key: String, map: Map<Long, Number>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k.toString(), v) }
        prefs.edit().putString(key, o.toString()).apply()
    }

    companion object {
        private const val PREFS = "radari_cfg"
        private const val KEY_LIMITS = "lim"
        private const val KEY_LENGTHS = "len"

        @Volatile private var instance: ThreatRepository? = null

        fun get(context: Context): ThreatRepository =
            instance ?: synchronized(this) {
                instance ?: ThreatRepository(context.applicationContext).also { instance = it }
            }

        private fun loadStatic(context: Context): List<Threat> {
            val text = context.resources.openRawResource(cg.radari.app.R.raw.radar_locations)
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            val out = ArrayList<Threat>(arr.length())
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.getJSONObject(i)
                val type = ThreatType.fromId(o.getInt("tip")) ?: continue
                out += Threat(
                    id = o.getLong("id"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    type = type,
                    city = o.optString("grad", ""),
                    name = o.optString("naziv", ""),
                )
            }
            return out
        }
    }
}
