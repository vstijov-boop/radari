package cg.radari.app.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import cg.radari.app.MainActivity
import cg.radari.app.R
import cg.radari.app.geo.Geo
import cg.radari.app.model.Threat
import cg.radari.app.model.ThreatRepository
import cg.radari.app.model.ThreatType
import cg.radari.app.team.TeamRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground servis (type=location): drzi GPS i TTS dok je pracenje ukljuceno,
 * radi i uz ugasen ekran, i jedini je izvor stanja koji i telefon i Android Auto
 * ekran posmatraju (StateFlow: nearest / section / lastVehicleFix).
 */
class TrackingService : LifecycleService(), LocationListener {

    companion object {
        const val CHANNEL_ID = "tracking"
        const val ACTION_START = "cg.radari.app.START"
        const val ACTION_STOP = "cg.radari.app.STOP"
        const val ACTION_TEST = "cg.radari.app.TEST"

        private val _state = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _state

        private val _nearest = MutableStateFlow<Nearest?>(null)
        val nearest: StateFlow<Nearest?> = _nearest

        /** (prosjek km/h, ogranicenje km/h, preostalo m) dok je dionica aktivna. */
        private val _sec = MutableStateFlow<Triple<Double, Int, Double>?>(null)
        val section: StateFlow<Triple<Double, Int, Double>?> = _sec

        /** Zadnji GPS fiks, za mapu na autu (konus + markere + vozilo). */
        private val _lastVehicleFix = MutableStateFlow<Fix?>(null)
        val lastVehicleFix: StateFlow<Fix?> = _lastVehicleFix

        private val _lastCueText = MutableStateFlow("")
        val lastCueText: StateFlow<String> = _lastCueText
    }

    private lateinit var repo: ThreatRepository
    private lateinit var engine: AlertEngine
    private lateinit var speaker: AlertSpeaker
    private lateinit var lm: LocationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var teamSyncJob: Job? = null

    /** Zadnji poznati smjer — kao `heading` u PWA, zadrzava se i kad vozilo uspori. */
    private var heading: Double? = null
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastTimeMs = 0L
    private var hasLast = false

    override fun onCreate() {
        super.onCreate()
        repo = ThreatRepository.get(this)
        speaker = AlertSpeaker(this)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        engine = AlertEngine(
            threats = { repo.all() },
            limitOf = ::limitOf,
            sectionLengthM = { repo.lengthOf(it) }, // 0 = racunaj iz koordinata (base * 1.05)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (intent?.action) {
            ACTION_STOP -> { stopTracking(); START_NOT_STICKY }
            ACTION_TEST -> { testAlert(); if (_state.value) START_STICKY else START_NOT_STICKY }
            else -> { startTracking(); START_STICKY }
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startTracking() {
        // Servis je pokrenut preko startForegroundService — notifikacija mora ici odmah,
        // i onda kada nema dozvole, inace sistem ubije proces.
        startAsForeground()
        if (!hasLocationPermission()) {
            _state.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (_state.value) return // vec radi; ponovljeni start ne resetuje stanja

        _state.value = true
        engine.reset()
        heading = null
        hasLast = false
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper(),
            )
        } catch (_: Exception) {
            // Nema GPS provajdera (npr. emulator) — cekamo; UI prikazuje "cekam signal".
        }
        // Sync patrola ekipe na ~20 s dok pracenje radi.
        teamSyncJob?.cancel()
        teamSyncJob = scope.launch {
            while (isActive) {
                lastVehicleFix.value?.let { f ->
                    runCatching { TeamRepo.sync(applicationContext, f.lat, f.lon) }
                }
                delay(20_000)
            }
        }
    }

    private fun stopTracking() {
        teamSyncJob?.cancel(); teamSyncJob = null
        runCatching { lm.removeUpdates(this) }
        _state.value = false
        _nearest.value = null
        _sec.value = null
        _lastVehicleFix.value = null
        _lastCueText.value = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf() // TTS se gasi u onDestroy
    }

    private fun testAlert() {
        speaker.enabled = true
        speaker.beep(2, AlertSpeaker.TONE_APPROACH)
        speaker.speak("Probna poruka. Radar za četristo metara, ograničenje osamdeset.")
        _lastCueText.value = getString(R.string.test_alert)
        // Proba se moze pustiti i kad pracenje ne radi; tada servis nema sta da drzi,
        // pa se gasi sam kad poruka prodje (ne prije, da TTS ne bude prekinut).
        if (!_state.value) scope.launch { delay(8_000); if (!_state.value) stopSelf() }
    }

    // ---- GPS callback ----

    override fun onLocationChanged(location: Location) {
        val now = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        var speed = location.speed.toDouble().takeIf { it >= 0 } ?: 0.0

        // Smjer i brzina kao u PWA: iz uzastopnih fiksova ako sam GPS ne daje.
        if (hasLast) {
            val seg = Geo.dist(lastLat, lastLon, location.latitude, location.longitude)
            val dt = (now - lastTimeMs) / 1000.0
            if (speed == 0.0 && dt > 0) speed = seg / dt
            if (seg > 4) heading = Geo.bearing(lastLat, lastLon, location.latitude, location.longitude)
        }
        if (location.hasBearing() && !location.bearing.isNaN() && speed > 1.5) {
            heading = location.bearing.toDouble()
        }
        lastLat = location.latitude
        lastLon = location.longitude
        lastTimeMs = now
        hasLast = true

        val kmh = speed * 3.6
        val fix = Fix(location.latitude, location.longitude, speed, heading)
        _lastVehicleFix.value = fix

        val tick = engine.onFix(fix, now)
        _nearest.value = tick.nearest
        _sec.value = tick.sectionAvgKmh?.let {
            Triple(it, tick.sectionLimitKmh ?: 0, tick.sectionLeftM ?: 0.0)
        }

        for (e in tick.cues) when (e.kind) {
            Cue.APPROACH -> {
                val text = approachText(e, kmh)
                speaker.beep(2, AlertSpeaker.TONE_APPROACH)
                speaker.speak(text)
                _lastCueText.value = text
            }
            Cue.CLOSE -> {
                speaker.beep(3, AlertSpeaker.TONE_CLOSE)
                _lastCueText.value = getString(R.string.cue_close)
            }
            Cue.SECTION_START -> {
                speaker.beep(2, AlertSpeaker.TONE_LOW)
                val t = "Ulaz u sekcijsko mjerenje, ograničenje ${e.limitKmh}."
                speaker.speak(t)
                _lastCueText.value = t
            }
            Cue.SECTION_OVER -> {
                speaker.beep(3, AlertSpeaker.TONE_CLOSE)
                val t = "Prosječna brzina iznad ograničenja. Usporite."
                speaker.speak(t, important = true)
                _lastCueText.value = t
            }
            Cue.SECTION_OK -> Unit // PWA samo resetuje zastavicu, bez zvuka
            Cue.SECTION_DONE -> {
                val avg = e.avgKmh.toInt()
                val over = avg > e.limitKmh
                speaker.beep(2, if (over) AlertSpeaker.TONE_CLOSE else AlertSpeaker.TONE_LOW)
                val t = "Kraj dionice. Prosječna brzina $avg na sat" +
                    if (over) ", iznad ograničenja." else "."
                speaker.speak(t)
                _lastCueText.value = t
            }
        }
    }

    private fun approachText(e: CueEvent, kmh: Double): String {
        val kind = e.threat?.type?.let { kindName(it) } ?: getString(R.string.threat_radar)
        var t = "$kind za ${e.distanceM.roundToHundred()} metara"
        if (e.limitKmh > 0) t += ", ograničenje ${e.limitKmh}"
        if (e.limitKmh > 0 && kmh > e.limitKmh + 5) t += ". Usporite."
        return t
    }

    private fun kindName(type: ThreatType): String = when (type) {
        ThreatType.INTERSECTION -> getString(R.string.threat_intersection)
        ThreatType.RADAR -> getString(R.string.threat_radar)
        ThreatType.SECTION_A -> getString(R.string.threat_section_start)
        ThreatType.SECTION_B -> getString(R.string.threat_section_end)
        ThreatType.PATROL -> getString(R.string.threat_patrol)
    }

    private fun limitOf(t: Threat): Int = repo.limitOf(t)

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(p: String?) {}
    override fun onProviderDisabled(p: String?) {}

    override fun onDestroy() {
        scope.cancel()
        if (this::lm.isInitialized) runCatching { lm.removeUpdates(this) }
        if (this::speaker.isInitialized) speaker.shutdown()
        _state.value = false
        super.onDestroy()
    }

    // ---- notifikacija ----

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Praćenje radara", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.tracking_on))
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notif)
        }
    }
}
