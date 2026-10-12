package cz.antonin.rovergnss.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.Track
import cz.antonin.rovergnss.export.ExportFilter
import cz.antonin.rovergnss.export.Exporter
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrackScreen(c: RoverController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracksFlow = remember { c.db.tracks().all() }
    val tracks by tracksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val rec by c.recording.collectAsStateWithLifecycle()

    var every by remember { mutableStateOf(c.prefs.recordEvery.toString()) }
    var antH by remember { mutableStateOf(c.prefs.antennaH.toString()) }
    var onlyFix by remember { mutableStateOf(false) }
    var skipMoving by remember { mutableStateOf(false) }
    var maxHAcc by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<Track, String>?>(null) }
    var toDelete by remember { mutableStateOf<Track?>(null) }

    fun filter() = ExportFilter(
        minQuality = if (onlyFix) setOf(4) else setOf(1, 2, 3, 4, 5),
        maxHAcc = maxHAcc.replace(',', '.').toDoubleOrNull() ?: 99.0,
        skipMoving = skipMoving,
    )

    fun export(uri: Uri?) {
        val (t, kind) = pending ?: return
        pending = null
        if (uri == null) return
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                val pts = c.db.tracks().points(t.id)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    if (kind == "csv") Exporter.csv(t, pts, filter(), out) else Exporter.gpx(t, pts, filter(), out)
                } ?: -1
            }
            c.postMessage(if (n >= 0) "Exportováno $n bodů" else "Soubor nelze zapsat")
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it) }
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { export(it) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Záznam") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rec == null) Button(onClick = { c.startRecording() }) { Text("Spustit záznam") }
                    else Button(onClick = { c.stopRecording() }) { Text("Ukončit (${rec?.points} b.)") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        every, {
                            every = it
                            it.toIntOrNull()?.takeIf { v -> v in 1..50 }?.let { v -> c.prefs.recordEvery = v }
                        },
                        label = { Text("Každá n-tá epocha") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        antH, {
                            antH = it
                            it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v in 0.0..10.0 }?.let { v -> c.prefs.antennaH = v }
                        },
                        label = { Text("Výška antény [m]") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Text(
                    "Rover posílá polohu frekvencí profilu (5 Hz). Výška antény se uloží k nové trase a odečte při exportu.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Section("Filtr exportu") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(onlyFix, { onlyFix = it })
                    Text("jen RTK fix")
                    Spacer(Modifier.width(12.dp))
                    Checkbox(skipMoving, { skipMoving = it })
                    Text("bez pohybu (IMU)")
                }
                OutlinedTextField(
                    maxHAcc, { maxHAcc = it }, label = { Text("Max. hAcc [m] (prázdné = vše)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    "CSV: ETRS89 (HAS přepočteno z ITRF2020), S-JTSK (7 parametrů, ~0,4 m), oddělovač ';'. GPX: ETRS89, výška nad geoidem u-blox.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Text("Trasy", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (tracks.isEmpty()) Text("Zatím žádná trasa.", modifier = Modifier.padding(horizontal = 16.dp))
        }
        items(tracks, key = { it.id }) { t ->
            val df = remember { SimpleDateFormat("d. M. yyyy HH:mm:ss", Locale.getDefault()) }
            Section(t.name) {
                val dur = ((t.endMs ?: System.currentTimeMillis()) - t.startMs) / 1000
                Text(
                    "${df.format(Date(t.startMs))}, ${dur / 60} min ${dur % 60} s, " +
                        (if (t.endMs == null) "probíhá" else "${t.points} bodů") +
                        if (t.antennaH != 0.0) ", anténa ${fmt(t.antennaH, 3)} m" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val safe = t.name.replace(Regex("[^A-Za-z0-9_-]+"), "_")
                    OutlinedButton(onClick = {
                        pending = t to "csv"
                        csvLauncher.launch("$safe.csv")
                    }, enabled = t.endMs != null) { Text("CSV") }
                    OutlinedButton(onClick = {
                        pending = t to "gpx"
                        gpxLauncher.launch("$safe.gpx")
                    }, enabled = t.endMs != null) { Text("GPX") }
                    TextButton(onClick = { toDelete = t }, enabled = t.endMs != null) { Text("Smazat") }
                }
            }
        }
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Smazat trasu?") },
            text = { Text("${t.name} – ${t.points} bodů. Nelze vrátit.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch { c.db.tracks().delete(t.id) }
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Zrušit") } },
        )
    }
}
