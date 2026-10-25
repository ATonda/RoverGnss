package cz.antonin.rovergnss.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.link.LinkState
import cz.antonin.rovergnss.service.AutoMeter
import cz.antonin.rovergnss.service.AutoTestDef
import cz.antonin.rovergnss.service.AutoTestKind
import cz.antonin.rovergnss.service.RoverController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Záložka Auto (0.9.0): měření rychlosti, zrychlení a brzdění, testy. „Aktivovat měření“ přepne rover do vnitřního
 * profilu Rychlost (10 Hz, IMU 120 Hz), „Ukončit měření“ ho vrátí. Rover leží na palubní desce – orientace se určí
 * sama z prvních rozjezdů a brzdění (do té doby zrychlení z rychlosti GNSS). Jen metrické jednotky.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoScreen(c: RoverController) {
    val active by c.speedActive.collectAsStateWithLifecycle()
    val live by c.autoMeter.live.collectAsStateWithLifecycle()
    val results by c.autoMeter.results.collectAsStateWithLifecycle()
    val link by c.linkState.collectAsStateWithLifecycle()
    var customKmh by remember { mutableStateOf(c.prefs.autoCustomKmh.toString()) }
    var askClear by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!active) Button(onClick = { c.startSpeed() }, enabled = link is LinkState.Connected) { Text("Aktivovat měření") }
            else OutlinedButton(onClick = { c.stopSpeed() }) { Text("Ukončit měření") }
            Text(
                when {
                    link !is LinkState.Connected -> "rover není připojený"
                    !active -> "rover ve svém profilu"
                    live.imuCalibrated -> "10 Hz, IMU 120 Hz, orientace určena"
                    else -> "10 Hz, IMU 120 Hz – orientace se určí z prvních rozjezdů a brzdění"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // rychlost
        Text(
            live.speedKmh?.takeIf { active }?.let { fmt(it, 0) } ?: "–",
            fontSize = 96.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text("km/h", modifier = Modifier.align(Alignment.CenterHorizontally))
        if (active) {
            KeyValue("Maximum", "${fmt(live.maxKmh, 1)} km/h")
            KeyValue("Průměr za jízdu", live.avgKmh?.let { "${fmt(it, 1)} km/h" } ?: "–")
            KeyValue("Průměr 10 s", live.avg10Kmh?.let { "${fmt(it, 1)} km/h" } ?: "–")
            KeyValue("Ujeto", fmtDist(live.distM))
            KeyValue(
                "Podélně", live.longG?.let { g -> (if (g >= 0) "zrychlení " else "brzdění ") + "${fmt(kotlin.math.abs(g), 2)} g" } ?: "–",
                valueColor = live.longG?.let { if (it < -0.05) Color(0xFFC62828) else if (it > 0.05) Color(0xFF2E7D32) else null },
            )
            KeyValue("Příčně", live.latG?.let { g -> "${fmt(kotlin.math.abs(g), 2)} g " + if (g >= 0) "doprava" else "doleva" } ?: "–")
            KeyValue("Max. zrychlení / brzdění / příčně", "${fmt(live.maxAccG, 2)} / ${fmt(live.maxBrakeG, 2)} / ${fmt(live.maxLatG, 2)} g")
        }

        // testy
        Text("Testy", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        val custom = customKmh.toIntOrNull()?.coerceIn(10, 300)
        val tests = AutoMeter.TESTS + listOfNotNull(custom?.let { AutoTestDef(AutoTestKind.ACCEL, 0, it) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tests.distinct().forEach { d ->
                FilterChip(
                    selected = live.armed == d,
                    onClick = { c.autoMeter.arm(if (live.armed == d) null else d) },
                    enabled = active,
                    label = { Text(d.label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                customKmh, { v ->
                    customKmh = v
                    v.toIntOrNull()?.let { if (it in 10..300) c.prefs.autoCustomKmh = it }
                },
                label = { Text("Vlastní rozjezd 0–X [km/h]") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        live.armed?.let { d ->
            Text("${d.label}: ${live.armedState}", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
        }
        if (!active) Text("Testy jdou nabít po aktivaci měření.", style = MaterialTheme.typography.bodySmall)

        // historie
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Výsledky", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (results.isNotEmpty()) {
                if (!askClear) TextButton(onClick = { askClear = true }) { Text("Smazat") }
                else TextButton(onClick = {
                    askClear = false
                    c.autoMeter.clearResults()
                }) { Text("Opravdu smazat") }
            }
        }
        if (results.isEmpty()) Text("Zatím žádné.", style = MaterialTheme.typography.bodySmall)
        val df = SimpleDateFormat("d. M. HH:mm", Locale.getDefault())
        results.asReversed().forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    "${r.label}: ${fmt(r.timeS, 2)} s" + if (!r.valid) "  (neplatné)" else "",
                    fontWeight = FontWeight.Bold,
                    color = if (r.valid) Color.Unspecified else Color(0xFF9E9E9E),
                )
                Text(
                    "${df.format(Date(r.atMs))}  dráha ${fmt(r.distM, 1)} m, konec ${fmt(r.vEndKmh, 1)} km/h, max ${fmt(r.maxG, 2)} g" +
                        (r.gradePct?.let { ", sklon ${fmt(it, 1)} %" } ?: "") + (if (r.note.isNotEmpty()) " – ${r.note}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            "Rozjezd a vzdálenost: zastav, po 1 s stání je test připravený a začne sám (okamžik rozjezdu z IMU). " +
                "Průjezd: zpomal pod výchozí rychlost a zrychli. Brzdění: zrychli nad výchozí rychlost a zabrzdi do zastavení. " +
                "Sklon nad 1 % je u výsledku uveden.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
