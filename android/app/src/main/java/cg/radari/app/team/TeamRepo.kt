package cg.radari.app.team

import android.content.Context
import cg.radari.app.BuildConfig
import cg.radari.app.model.Threat
import cg.radari.app.model.ThreatRepository
import cg.radari.app.model.ThreatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Patrole ekipe preko Supabase (PostgREST).
 * Tabela `patrols`: id bigint, lat double precision, lon double precision,
 *                   note text, created_at timestamptz (default now()).
 * URL i anon kljuc dolaze iz local.properties -> BuildConfig; ako nisu postavljeni,
 * modul se tiho iskljuci i aplikacija radi samo sa zvanicnom bazom.
 */
object TeamRepo {

    const val RESULT_SENT = "sent"
    const val RESULT_QUEUED = "queued"
    const val RESULT_OFF = "off"

    private val baseUrl: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey: String get() = BuildConfig.SUPABASE_ANON_KEY

    /** true kad su URL i kljuc upisani u local.properties. */
    val configured: Boolean get() = baseUrl.isNotBlank() && anonKey.isNotBlank()

    private const val TABLE = "patrols"
    private const val MAX_AGE_MS = 2 * 60 * 60 * 1000L // patrole starije od 2 h ispadaju

    private const val PREFS = "team_outbox"
    private const val KEY = "pending"

    /** Posalji patrolu; bez interneta ostaje u redu i salje se pri iducem syncu. */
    suspend fun reportPatrol(context: Context, lat: Double, lon: Double, note: String): String =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext RESULT_OFF
            val body = JSONObject().put("lat", lat).put("lon", lon).put("note", note)
            if (post(body)) RESULT_SENT else { enqueue(context, body); RESULT_QUEUED }
        }

    /** Povuci patrole u okolini pozicije i osvjezi repozitorij. */
    suspend fun sync(context: Context, lat: Double, lon: Double) = withContext(Dispatchers.IO) {
        if (!configured) return@withContext
        flushOutbox(context)
        val url = "$baseUrl/rest/v1/$TABLE" +
            "?select=id,lat,lon,note,created_at" +
            "&created_at=gte.${isoCutoff()}" +
            "&lat=gte.${lat - 0.1}&lat=lte.${lat + 0.1}" +
            "&lon=gte.${lon - 0.15}&lon=lte.${lon + 0.15}"
        val json = get(url) ?: return@withContext

        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return@withContext
        val now = System.currentTimeMillis()
        val list = ArrayList<Threat>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val created = parseIso(o.optString("created_at"))
            // Bez upotrebljivog vremena patrolu ne prikazujemo — ne zna joj se starost.
            if (created <= 0 || now - created >= MAX_AGE_MS) continue
            list += Threat(
                id = TEAM_ID_BASE + o.optLong("id", i.toLong()),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                type = ThreatType.PATROL,
                city = "ekipa",
                name = o.optString("note", "patrola"),
                team = true,
                createdAt = created,
            )
        }
        ThreatRepository.get(context).setTeam(list)
    }

    // ---- outbox ----

    private fun enqueue(context: Context, body: JSONObject) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(sp.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        arr.put(body)
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private fun flushOutbox(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(sp.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        if (arr.length() == 0) return
        val remaining = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (!post(item)) remaining.put(item)
        }
        sp.edit().putString(KEY, remaining.toString()).apply()
    }

    // ---- HTTP ----

    private fun conn(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer $anonKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 8000
            readTimeout = 8000
        }

    private fun get(url: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = conn(url, "GET")
            if (c.responseCode in 200..299) {
                c.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun post(body: JSONObject): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = conn("$baseUrl/rest/v1/$TABLE", "POST")
            c.setRequestProperty("Prefer", "return=minimal")
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            c.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            c?.disconnect()
        }
    }

    // ---- vrijeme ----

    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun isoCutoff(): String =
        fmt("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date(System.currentTimeMillis() - MAX_AGE_MS))

    /**
     * Supabase vraca npr. "2026-09-09T14:03:11.482231+00:00" — SimpleDateFormat ne jede
     * ni frakcije ni "+00:00", pa ih odsijecemo i citamo kao UTC.
     */
    private fun parseIso(s: String): Long {
        if (s.isBlank()) return 0L
        val core = s.substringBefore('.').substringBefore('+').removeSuffix("Z").take(19)
        return runCatching { fmt("yyyy-MM-dd'T'HH:mm:ss").parse(core)?.time ?: 0L }.getOrDefault(0L)
    }

    /** Id-ovi patrola ne smiju se sudariti s bazom od 88 lokacija. */
    private const val TEAM_ID_BASE = 1_000_000L
}
