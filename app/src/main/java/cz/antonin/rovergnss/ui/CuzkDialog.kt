package cz.antonin.rovergnss.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cz.antonin.rovergnss.cuzk.CuzkPoint
import cz.antonin.rovergnss.cuzk.CuzkPoints
import cz.antonin.rovergnss.geo.Angles

/** Body bodových polí ČÚZK v okolí klepnutí; „Převzít“ uloží bod do aktivní zakázky. */
@Composable
fun CuzkDialog(tap: MapTap, onTake: (CuzkPoint) -> Unit, onDismiss: () -> Unit) {
    val lat = tap.lat
    val lon = tap.lon
    val radius = remember(tap) { CuzkPoints.searchRadius(tap.zoom, tap.lat).toInt() }
    val context = LocalContext.current
    var result by remember { mutableStateOf<List<CuzkPoint>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var taken by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(tap) {
        try {
            result = CuzkPoints.near(lat, lon, tap.zoom, tap.widthDp, tap.heightDp)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bodová pole ČÚZK") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    error != null -> Text("Dotaz selhal: $error", color = MaterialTheme.colorScheme.error)
                    result == null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text("Hledám body do $radius m…")
                    }
                    result!!.isEmpty() -> Text("Do $radius m od místa žádný bod. Přibliž mapu a klepni přímo na značku bodu.")
                    else -> result!!.forEach { p ->
                        val key = p.layer + p.name
                        val (d, _) = Angles.distanceBearing(lat, lon, p.lat, p.lon)
                        Column(Modifier.fillMaxWidth()) {
                            Text("${p.name}  (${p.layer}${if (p.kind.isNotEmpty()) ", " + p.kind else ""})", style = MaterialTheme.typography.titleSmall)
                            if (p.place.isNotEmpty()) Text(p.place, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Y ${fmt(p.y, 2)}  X ${fmt(p.x, 2)}  Bpv ${fmt(p.bpv, 3)}",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            )
                            if (p.etrsLat != null && p.etrsLon != null) Text(
                                "ETRS89 ${Angles.dms(p.etrsLat, 5)}  ${Angles.dms(p.etrsLon, 5)}  h ${fmt(p.etrsH, 3)}",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            )
                            Text("${fmtDist(d)} od klepnutí", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(onClick = {
                                    onTake(p)
                                    taken = taken + key
                                }, enabled = key !in taken) { Text(if (key in taken) "Převzato" else "Převzít") }
                                p.url?.let { u -> TextButton(onClick = { openUrl(context, u) }) { Text("Geod. údaje") } }
                                TextButton(onClick = { expanded = if (expanded == key) null else key }) { Text("Pole") }
                            }
                            if (expanded == key) Text(
                                p.attributes.entries.joinToString("\n") { (k, v) -> "$k = $v" },
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
    )
}
