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
    val job by c.activeJob.collectAsStateWithLifecycle()
    val tracksFlow = remember(job?.id) { job?.let { c.db.tracks().byJob(it.id) } ?: kotlinx.coroutines.flow.emptyFlow() }
    val tracks by tracksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val rec by c.recording.collectAsStateWithLifecycle()
    // trasy měření bodů („M …“): trasa → název bodu
    val measFlow = remember(job?.id) { job?.let { c.db.measurements().byJob(it.id) } ?: kotlinx.coroutines.flow.emptyFlow() }
    val meas by measFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val pointsFlow = remember(job?.id) { job?.let { c.db.points().byJob(it.id) } ?: kotlinx.coroutines.flow.emptyFlow() }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val trackPoint = remember(meas, points) {
        val names = points.associate { it.id to it.name }
        meas.filter { it.trackId != null }.associate { it.trackId!! to (names[it.pointId] ?: "?") }
    }
    var onlyFix by remember { mutableStateOf(false) }
    var skipMoving by remember { mutableStateOf(false) }
    var maxHAcc by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<Track, String>?>(null) }
    var toDelete by remember { mutableStateOf<Track?>(null) }
    var toRename by remember { mutableStateOf<Track?>(null) }
    var newName by remember { mutableStateOf("") }

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
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        when (kind) {
                            "csv" -> Exporter.csv(t, c.db.tracks().points(t.id), filter(), out)
                            "gpx" -> Exporter.gpx(t, c.db.tracks().points(t.id), filter(), out)
                            "diag" -> Exporter.diagCsv(c.db.tracks().diag(t.id), out)
                            else -> t.rawFile?.let { f -> c.rawFile(f).inputStream().use { it.copyTo(out) }.toInt() } ?: -1
                        }
                    } ?: -1
                } catch (e: java.io.IOException) {
                    -1
                }
            }
            c.postMessage(
                when {
                    n < 0 -> "Soubor nelze zapsat"
                    kind == "raw" -> "Exportováno ${n / 1024} kB"
                    kind == "diag" -> "Exportováno $n záznamů diagnostiky"
                    else -> "Exportováno $n bodů"
                }
            )
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it) }
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { export(it) }
    val rawLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { export(it) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Záznam") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rec == null) Button(onClick = { c.startRecording() }) { Text("Spustit záznam") }
                    else Button(onClick = { c.stopRecording("tlačítko Ukončit v Trasách", fromUi = true) }) { Text("Ukončit (${rec?.points} b.)") }
                }
                Text(
                    "Výška antény ${antCm(c.prefs.antennaH)}, každá ${c.prefs.recordEvery}. epocha" +
                        (if (c.prefs.recordRaw) ", se surovým proudem" else "") + " – mění se v uživatelském profilu (Nastavení). " +
                        "K trase se ukládá i diagnostika 1 Hz (stav roveru, satelity, BT, NTRIP).",
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
                    "CSV: ETRS89 (HAS přepočteno z ITRF2020), S-JTSK a Bpv převodem ČÚZK, oddělovač ';'. GPX: ETRS89, výška nad geoidem u-blox.",
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
                trackPoint[t.id]?.let { Text("měření bodu $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                val dur = ((t.endMs ?: System.currentTimeMillis()) - t.startMs) / 1000
                Text(
                    "${df.format(Date(t.startMs))}, ${dur / 60} min ${dur % 60} s, " +
                        (if (t.endMs == null) "probíhá" else "${t.points} bodů") +
                        if (t.antennaH != 0.0) ", anténa ${antCm(t.antennaH)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                // jména souborů podle začátku trasy: RG_20261012_191607_body.csv …
                val safe = "RG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(t.startMs))
                val done = t.endMs != null
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pending = t to "csv"
                        csvLauncher.launch("${safe}_body.csv")
                    }, enabled = done) { Text("CSV") }
                    OutlinedButton(onClick = {
                        pending = t to "gpx"
                        gpxLauncher.launch("$safe.gpx")
                    }, enabled = done) { Text("GPX") }
                    OutlinedButton(onClick = {
                        pending = t to "diag"
                        csvLauncher.launch("${safe}_diag.csv")
                    }, enabled = done) { Text("Diag") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (t.rawFile != null) OutlinedButton(onClick = {
                        pending = t to "raw"
                        rawLauncher.launch("${safe}_raw.log")
                    }, enabled = done) { Text("Surový proud") }
                    TextButton(onClick = {
                        newName = t.name
                        toRename = t
                    }) { Text("Přejmenovat") }
                    TextButton(onClick = { toDelete = t }, enabled = done) { Text("Smazat") }
                }
            }
        }
    }

    toRename?.let { t ->
        AlertDialog(
            onDismissRequest = { toRename = null },
            title = { Text("Přejmenovat trasu") },
            text = {
                OutlinedTextField(newName, { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    toRename = null
                    c.renameTrack(t, newName)
                }, enabled = newName.isNotBlank()) { Text("Uložit") }
            },
            dismissButton = { TextButton(onClick = { toRename = null }) { Text("Zrušit") } },
        )
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Smazat trasu?") },
            text = { Text("${t.name} – ${t.points} bodů. Nelze vrátit.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    c.deleteTrack(t)
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Zrušit") } },
        )
    }
}
