package cg.radari.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cg.radari.app.alerts.TrackingService
import cg.radari.app.model.ThreatType
import cg.radari.app.team.TeamRepo
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** Servis se pokrece tek kad korisnik odgovori na dijalog dozvola. */
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val ok = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                hasLocation()
            if (ok) launchService() else permissionDenied = true
        }

    private val permLauncherNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var permissionDenied by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RadariScreen() }
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun launchService() {
        permissionDenied = false
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
    }

    private fun startTracking() {
        if (hasLocation()) {
            // Notifikacije se traze uz put; bez njih pracenje i dalje radi.
            if (Build.VERSION.SDK_INT >= 33) {
                permLauncherNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            launchService()
            return
        }
        permLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun stopTracking() {
        // Obicni startService: aktivnost je u prvom planu, pa nema obaveze startForeground.
        startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
    }

    private fun testAlert() {
        startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_TEST))
    }

    @Composable
    private fun RadariScreen() {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = BG,
                surface = CARD,
                primary = Color(0xFF4a90d9),
            ),
        ) {
            val running by TrackingService.running.collectAsState()
            val nearest by TrackingService.nearest.collectAsState()
            val section by TrackingService.section.collectAsState()
            val cue by TrackingService.lastCueText.collectAsState()
            val scope = rememberCoroutineScope()
            var patrolMsg by remember { mutableStateOf("") }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.Bold)

                Button(
                    onClick = { if (running) stopTracking() else startTracking() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (running) R.string.tracking_stop else R.string.tracking_start))
                }

                if (permissionDenied) {
                    Text(stringResource(R.string.car_permission_needed), color = WARN)
                }

                OutlinedButton(
                    onClick = { testAlert() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.test_alert)) }

                // Najbliza pretnja
                val n = nearest
                Card(colors = CardDefaults.cardColors(containerColor = CARD)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (n == null) {
                            Text(
                                stringResource(if (running) R.string.no_threat else R.string.tracking_off),
                                color = MUTED,
                            )
                        } else {
                            Text(fmtDist(n.distanceM), fontSize = 42.sp, fontWeight = FontWeight.Bold)
                            Text(
                                kindName(n.threat.type) +
                                    (n.threat.city.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                color = MUTED,
                            )
                            if (n.threat.name.isNotBlank()) Text(n.threat.name, color = MUTED, fontSize = 13.sp)
                            if (n.limitKmh > 0) Text("Ograničenje ${n.limitKmh} km/h")
                        }
                        if (cue.isNotBlank()) Text(cue, color = WARN)
                    }
                }

                // Sekcijsko mjerenje
                val s = section
                if (s != null && s.second > 0) {
                    val (avg, lim, leftM) = s
                    val over = avg > lim
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (over) Color(0xFF2e1412) else CARD,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Sekcijsko mjerenje · prosjek ${avg.toInt()} km/h")
                            Text(
                                "Ograničenje $lim km/h · do izlaza " +
                                    String.format(Locale.US, "%.1f", leftM / 1000.0) + " km",
                                color = MUTED,
                            )
                            LinearProgressIndicator(
                                progress = { (avg / lim).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // Patrola ekipe na trenutnoj GPS poziciji
                Button(
                    onClick = {
                        val fix = TrackingService.lastVehicleFix.value
                        if (fix == null) {
                            patrolMsg = getString(R.string.no_gps)
                        } else {
                            scope.launch {
                                val r = runCatching {
                                    TeamRepo.reportPatrol(applicationContext, fix.lat, fix.lon, "patrola")
                                }
                                patrolMsg = when {
                                    r.getOrNull() == TeamRepo.RESULT_SENT -> getString(R.string.patrol_sent)
                                    r.isSuccess -> getString(R.string.patrol_queue)
                                    else -> getString(R.string.error_prefix) + " " +
                                        (r.exceptionOrNull()?.message ?: "")
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.patrol_here)) }
                if (patrolMsg.isNotBlank()) Text(patrolMsg, color = MUTED)
            }
        }
    }

    @Composable
    private fun kindName(type: ThreatType): String = stringResource(
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
        val BG = Color(0xFF0d141d)
        val CARD = Color(0xFF141e2b)
        val MUTED = Color(0xFF7d90a8)
        val WARN = Color(0xFFf2b21c)
    }
}
