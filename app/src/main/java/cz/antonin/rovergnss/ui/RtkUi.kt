package cz.antonin.rovergnss.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.link.LinkState
import cz.antonin.rovergnss.ntrip.NtripClient
import cz.antonin.rovergnss.ntrip.NtripState
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tlačítko RTK v Živě pod REC (0.9.0): zap / vyp NTRIP podle vybraného profilu v Nastavení → Korekce NTRIP.
 * Barva: šedá vypnuto, žlutá připojuje / čeká na polohu, zelená RTCM chodí (pod tím doba spojení), červená chyba.
 */
@Composable
fun RtkButton(c: RoverController) {
    val state by c.ntripState.collectAsStateWithLifecycle()
    val usage by c.rtkUsage.state.collectAsStateWithLifecycle()
    val link by c.linkState.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val (color, text) = when (val s = state) {
        NtripState.Off -> Color(0xFF757575) to " RTK"
        is NtripState.Connecting -> Color(0xFFF9A825) to if (s.lastError == NtripClient.WAIT_FIX) " RTK čeká na polohu" else " RTK připojuji"
        is NtripState.Streaming -> {
            val age = (now - c.ntrip.lastDataMs) / 1000
            Color(0xFF2E7D32) to " RTK ${hms(usage.currentSec ?: 0)}" + if (age > 5) " (RTCM ${age} s)" else ""
        }
        is NtripState.Failed -> Color(0xFFC62828) to " RTK chyba"
    }
    Surface(
        onClick = {
            when (state) {
                NtripState.Off, is NtripState.Failed -> {
                    val p = c.prefs.ntripProfiles.getOrNull(c.prefs.ntripIndex)
                    when {
                        p == null -> c.postMessage("Nejdřív nastav profil v Nastavení → Korekce NTRIP")
                        link !is LinkState.Connected -> c.postMessage("Rover není připojený")
                        else -> c.startNtrip(p)
                    }
                }
                else -> c.stopNtrip()
            }
        },
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.85f),
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text(text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Nastavení → Korekce NTRIP: doba připojení, cena za hodinu (ručně), celková cena od vynulování. */
@Composable
fun RtkCostSection(c: RoverController) {
    val usage by c.rtkUsage.state.collectAsStateWithLifecycle()
    var price by remember { mutableStateOf(if (usage.pricePerHour > 0) fmt(usage.pricePerHour, 2) else "") }
    var ask by remember { mutableStateOf(false) }
    Text("Doba a cena RTK", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    KeyValue("Doba od vynulování", hms(usage.usedSec) + (usage.currentSec?.let { " (teď ${hms(it)})" } ?: ""))
    KeyValue("Spojení", usage.sessions.toString() + if (usage.currentSec != null) " + probíhající" else "")
    KeyValue("Cena celkem", if (usage.pricePerHour > 0) "${fmt(usage.cost, 2)} Kč" else "zadej cenu za hodinu")
    KeyValue("Vynulováno", if (usage.resetMs > 0) SimpleDateFormat("d. M. yyyy HH:mm", Locale.getDefault()).format(Date(usage.resetMs)) else "nikdy")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            price, { v ->
                price = v
                v.replace(',', '.').toDoubleOrNull()?.let { c.rtkUsage.setPrice(it) }
            },
            label = { Text("Cena [Kč/h]") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        if (!ask) OutlinedButton(onClick = { ask = true }) { Text("Vynulovat") }
        else OutlinedButton(onClick = {
            ask = false
            c.rtkUsage.reset()
        }) { Text("Opravdu vynulovat") }
    }
    Text(
        "Účtuje se každá započatá sekunda spojení s casterem. Při nečinnosti (rover odpojený > 1 min, nebo bez záznamu " +
            "a měření a rover stojí > 10 min) přijde upozornění, RTK se samo nevypne.",
        style = MaterialTheme.typography.bodySmall,
    )
}

fun hms(sec: Long): String = String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)

/** Živě (0.9.0): jeden řádek družic – použité / sledované po konstelacích a průměrné C/N0 použitých. */
@Composable
fun SatLine(c: RoverController) {
    val sats by c.sats.collectAsStateWithLifecycle()
    if (sats.isEmpty()) return
    val used = sats.filter { it.used }
    fun part(name: String, id: Int) = sats.count { it.gnssId == id }.takeIf { it > 0 }?.let { n -> "$name ${used.count { it.gnssId == id }}/$n" }
    val parts = listOfNotNull(part("G", 0), part("E", 2), part("B", 3), part("R", 6))
    val cn0 = if (used.isNotEmpty()) used.map { it.cno }.average() else null
    Text(
        "Družice ${used.size}/${sats.size}  " + parts.joinToString("  ") + (cn0?.let { "  C/N0 ${fmt(it, 0)} dBHz" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
    )
}
