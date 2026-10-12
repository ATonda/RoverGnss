package cz.antonin.rovergnss.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.data.PointIo
import cz.antonin.rovergnss.data.PointSource
import cz.antonin.rovergnss.geo.Angles
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PointsScreen(c: RoverController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by c.activeJob.collectAsStateWithLifecycle()
    val pos by c.pos.collectAsStateWithLifecycle()
    val pointsFlow = remember(job?.id) { job?.let { c.db.points().byJob(it.id) } ?: emptyFlow() }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<Point?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val j = c.ensureJob()
            val r = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { PointIo.import(it, j.id) }
                } catch (e: Exception) {
                    PointIo.ImportResult(emptyList(), 0, e.message)
                }
            }
            if (r == null || r.error != null) {
                c.postMessage("Import selhal: ${r?.error ?: "soubor nelze otevřít"}")
                return@launch
            }
            withContext(Dispatchers.IO) { c.db.points().insertAll(r.points) }
            c.postMessage("Načteno ${r.points.size} bodů" + if (r.skipped > 0) ", vynecháno ${r.skipped} řádků" else "")
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { PointIo.export(points, it) } ?: -1
                } catch (e: Exception) {
                    -1
                }
            }
            c.postMessage(if (n >= 0) "Exportováno $n bodů" else "Soubor nelze zapsat")
        }
    }

    Column(Modifier.fillMaxSize()) {
        JobBar(c)
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val j = c.ensureJob()
                    editing = Point(jobId = j.id, name = "")
                }
            }) { Text("Nový bod") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import") }
            OutlinedButton(onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                exportLauncher.launch("RG_${stamp}_body_zakazky.csv")
            }, enabled = points.isNotEmpty()) { Text("Export") }
        }
        Text(
            "Body bodových polí ČÚZK: na mapě (Živě) zapni vrstvu ČÚZK a klepni na bod.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        HorizontalDivider()
        if (points.isEmpty()) {
            Text("Zakázka nemá žádné body.", modifier = Modifier.padding(16.dp))
        }
        val g = pos?.etrs()
        LazyColumn(Modifier.fillMaxSize()) {
            items(points, key = { it.id }) { p ->
                Column(
                    Modifier.fillMaxWidth().clickable { editing = p }.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(
                            listOf(p.kind, PointSource.NAMES.getOrElse(p.source) { "" }).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Y ${fmt(p.y, 2)}  X ${fmt(p.x, 2)}" + (if (p.jtskDerived) " (dopočteno)" else "") +
                            "  Bpv ${fmt(p.bpv, 2)}",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    )
                    if (g != null && p.lat != null && p.lon != null) {
                        val (d, az) = Angles.distanceBearing(g.lat, g.lon, p.lat, p.lon)
                        Text("od roveru ${fmtDist(d)}, směr ${fmt(az, 0)}°", style = MaterialTheme.typography.bodySmall)
                    }
                    p.url?.let { u ->
                        Text(
                            "geodetické údaje", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable { openUrl(context, u) },
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { p ->
        PointEditor(
            initial = p, rover = pos, antennaH = c.prefs.antennaH,
            onSave = {
                c.savePoint(it)
                editing = null
            },
            onDelete = if (p.id != 0L) ({
                c.deletePoint(p)
                editing = null
            }) else null,
            onDismiss = { editing = null },
        )
    }
}

fun fmtDist(d: Double): String = if (d < 1000) "${fmt(d, if (d < 10) 2 else 1)} m" else "${fmt(d / 1000, 2)} km"

fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}
