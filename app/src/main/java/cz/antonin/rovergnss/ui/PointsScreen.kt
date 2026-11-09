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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
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
import cz.antonin.rovergnss.export.Exporter
import cz.antonin.rovergnss.export.FileNames
import cz.antonin.rovergnss.geo.Angles
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@Composable
fun PointsScreen(c: RoverController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by c.activeJob.collectAsStateWithLifecycle()
    val pos by c.pos.collectAsStateWithLifecycle()
    val pointsFlow = remember(job?.id) { job?.let { c.db.points().byJob(it.id) } ?: emptyFlow() }
    val pointsByName by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // jen změřené body REC (0.13.0 – zadané a ČÚZK body jsou ve Vytyčování), nejnovější nahoře
    val points = remember(pointsByName) {
        pointsByName.filter { it.source == PointSource.MEASURED }.sortedByDescending { it.createdMs }
    }
    var show3d by remember { mutableStateOf(false) }
    val measFlow = remember(job?.id) { job?.let { c.db.measurements().byJob(it.id) } ?: emptyFlow() }
    val meas by measFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val measByPoint = remember(meas) { meas.filter { it.endMs != null }.groupBy { it.pointId } }
    var editing by remember { mutableStateOf<Point?>(null) }
    // výběr bodů k exportu (ZIP: surový proud .log každého bodu + souhrn.csv)
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var askDelete by remember { mutableStateOf(false) }
    var askMove by remember { mutableStateOf(false) }
    val lastMeas = remember(meas) { meas.filter { it.endMs != null }.groupBy { it.pointId }.mapValues { it.value.last() } }
    fun exportPoints(uri: Uri?, logs: Boolean) {
        if (uri == null) return
        val chosen = points.filter { it.id in selected }
        val jName = job?.name ?: "lokalita"
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    val m = lastMeas.filterKeys { k -> chosen.any { it.id == k } }
                    val files = m.values.associate { mm ->
                        mm.id to mm.trackId?.let { t -> c.db.tracks().get(t)?.rawFile?.let { c.rawFile(it) } }
                    }
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        if (logs) Exporter.pointsZip(jName, chosen, m, true, { files[it.id] }, out)
                        else Exporter.pointsSummaryCsv(jName, chosen, m, out)
                    } ?: -1
                } catch (e: Exception) {
                    -1
                }
            }
            c.postMessage(
                when {
                    n < 0 -> "Soubor nelze zapsat"
                    logs -> "Exportováno ${chosen.size} bodů ($n souborů v ZIPu)"
                    else -> "Exportováno $n bodů (souhrn)"
                }
            )
            selecting = false
            selected = emptySet()
        }
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { exportPoints(it, true) }
    val sumLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { exportPoints(it, false) }

    val measLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    val j = c.ensureJob()
                    val map = c.db.points().listByJob(j.id).filter { it.source == PointSource.MEASURED }.associateBy { it.id }
                    val list = c.db.measurements().listByJob(j.id).filter { it.pointId in map }
                    context.contentResolver.openOutputStream(uri)?.use { Exporter.measurementsCsv(map, list, it) } ?: -1
                } catch (e: Exception) {
                    -1
                }
            }
            c.postMessage(if (n >= 0) "Exportováno $n měření" else "Soubor nelze zapsat")
        }
    }
    var exportMenu by remember { mutableStateOf(false) }
    val kmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { Exporter.pointsKml(job?.name ?: "Body", points, it) } ?: -1
                } catch (e: Exception) {
                    -1
                }
            }
            c.postMessage(if (n >= 0) "Exportováno $n bodů (KML)" else "Soubor nelze zapsat")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { show3d = true }, enabled = points.any { it.lat != null && it.hEll != null }) { Text("3D") }
            OutlinedButton(onClick = {
                selecting = !selecting
                selected = emptySet()
            }, enabled = points.isNotEmpty()) { Text(if (selecting) "Konec výběru" else "Vybrat") }
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { exportMenu = true }, enabled = points.isNotEmpty()) { Text("Export") }
                androidx.compose.material3.DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Vybrat body – ZIP (.log + souhrn) …") }, onClick = {
                        exportMenu = false
                        selecting = true
                        selected = emptySet()
                    })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("CSV měření (každé měření, výsledek, shluky)") }, onClick = {
                        exportMenu = false
                        measLauncher.launch(FileNames.job(job?.name ?: "lokalita", "mereni", System.currentTimeMillis(), "csv"))
                    })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("KML (Google Earth, mapy)") }, onClick = {
                        exportMenu = false
                        kmlLauncher.launch(FileNames.job(job?.name ?: "lokalita", "body", System.currentTimeMillis(), "kml"))
                    })
                }
            }
        }
        if (selecting) {
            Text(
                "Vybráno ${selected.size} z ${points.size}",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp),
            )
            Row(
                Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(onClick = { selected = points.map { it.id }.toSet() }) { Text("Vše") }
                OutlinedButton(onClick = { selected = emptySet() }) { Text("Nic") }
                Button(onClick = {
                    zipLauncher.launch(FileNames.jobDate(job?.name ?: "lokalita", System.currentTimeMillis(), "zip"))
                }, enabled = selected.isNotEmpty()) { Text("ZIP") }
                OutlinedButton(onClick = {
                    sumLauncher.launch(FileNames.job(job?.name ?: "lokalita", "souhrn", System.currentTimeMillis(), "csv"))
                }, enabled = selected.isNotEmpty()) { Text("Jen souhrn") }
                OutlinedButton(onClick = { askMove = true }, enabled = selected.isNotEmpty()) { Text("Do lokality…") }
                OutlinedButton(onClick = { askDelete = true }, enabled = selected.isNotEmpty()) {
                    Text("Smazat", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = {
                    selecting = false
                    selected = emptySet()
                }) { Text("Zrušit") }
            }
            Text(
                "ZIP: surový proud .log každého bodu (hlavička a souhrn uvnitř) a souhrn.csv – jeden řádek na bod, " +
                    "Y/X S-JTSK a E_5514/N_5514 pro QGIS (načíst jako text s oddělovačem ';', CRS EPSG:5514).",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Text(
            "Změřené body (REC). Zadané body a body ČÚZK jsou ve Vytyčování.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        HorizontalDivider()
        if (points.isEmpty()) {
            Text("Lokalita nemá změřené body – změř je tlačítkem REC v Živě.", modifier = Modifier.padding(16.dp))
        }
        val g = pos?.etrs()
        LazyColumn(Modifier.fillMaxSize()) {
            items(points, key = { it.id }) { p ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        if (selecting) selected = if (p.id in selected) selected - p.id else selected + p.id
                        else editing = p
                    }.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) androidx.compose.material3.Checkbox(
                            p.id in selected, { on -> selected = if (on) selected + p.id else selected - p.id },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(dateFmt.format(Date(p.createdMs)), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            listOf(if (p.control) "kontrolní" else "", p.kind, PointSource.NAMES.getOrElse(p.source) { "" })
                                .filter { it.isNotEmpty() }.joinToString(" · "),
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
                    lastMeas[p.id]?.takeIf { it.result != cz.antonin.rovergnss.data.PointResult.NONE }?.let { m ->
                        Text(
                            cz.antonin.rovergnss.data.PointResult.name(m.result) +
                                (m.fixPct?.let { " · fix ${it.toInt()} %" } ?: "") +
                                (m.sMeanH?.let { " · σ ${cz.antonin.rovergnss.data.PointEvaluator.cm(it)} cm" } ?: "") +
                                (if (m.clusters >= 2) " · PODEZŘELÝ FIX" else ""),
                            color = if (m.result == cz.antonin.rovergnss.data.PointResult.OK && m.clusters < 2)
                                androidx.compose.ui.graphics.Color(0xFF2E7D32) else androidx.compose.ui.graphics.Color(0xFFC62828),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (p.hOffset != 0.0) Text(
                        "excentr výšky ${fmt(p.hOffset, 3)} m (v h a Bpv)", style = MaterialTheme.typography.bodySmall,
                    )
                    // odchylka posledního měření od bodu: dané body a přeměřené (kontrolní) body
                    measByPoint[p.id]?.takeIf { p.source != PointSource.MEASURED || it.size > 1 }?.let { ms ->
                        val last = ms.last()
                        val d2 = if (last.dN != null && last.dE != null) kotlin.math.hypot(last.dN, last.dE) else null
                        Text(
                            "měření ${ms.size}×, poslední 2D ${fmt(d2, 3)} m" +
                                ((last.dH ?: last.dU)?.let { ", výška ${fmt(it, 3)} m" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
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

    if (askMove) MovePointsDialog(c, job?.id, selected.size, onDismiss = { askMove = false }) { toJob, copy ->
        askMove = false
        c.movePoints(points.filter { it.id in selected }, toJob, copy)
        selected = emptySet()
        selecting = false
    }

    if (askDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("Smazat ${selected.size} bodů?") },
        text = { Text("Body i s měřeními a soubory (surový proud). Nelze vrátit.") },
        confirmButton = {
            TextButton(onClick = {
                askDelete = false
                c.deletePoints(points.filter { it.id in selected })
                selected = emptySet()
                selecting = false
            }) { Text("Smazat", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Zrušit") } },
    )

    if (show3d) Points3DScreen(c, points, lastMeas, onClose = { show3d = false })

    editing?.let { p ->
        PointEditor(
            initial = p,
            onSave = {
                c.savePoint(it)
                editing = null
            },
            onDelete = if (p.id != 0L) ({
                c.deletePoint(p)
                editing = null
            }) else null,
            onDismiss = { editing = null },
            extra = { PointExtras(c, p, onClose = { editing = null }) },
        )
    }
}

private val dateFmt = java.text.SimpleDateFormat("d. M. yyyy HH:mm", java.util.Locale.getDefault())

/**
 * Přesun / kopie bodů do jiné lokality (0.12.0): výběr existující lokality nebo nová. [onConfirm] (id lokality, kopie).
 */
@Composable
fun MovePointsDialog(c: RoverController, fromJob: Long?, count: Int, onDismiss: () -> Unit, onConfirm: (Long, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val jobsFlow = remember { c.db.jobs().all() }
    val jobs by jobsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var target by remember { mutableStateOf<Long?>(null) }   // null = nová lokalita
    var newName by remember { mutableStateOf("") }
    var copy by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Bod do jiné lokality" else "$count bodů do jiné lokality") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(!copy, { copy = false }, label = { Text("Přesunout") })
                    androidx.compose.material3.FilterChip(copy, { copy = true }, label = { Text("Kopírovat") })
                }
                jobs.filter { it.id != fromJob }.forEach { j ->
                    Row(Modifier.fillMaxWidth().clickable { target = j.id }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(target == j.id, { target = j.id })
                        Text(j.name)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { target = null }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(target == null, { target = null })
                    Text("Nová lokalita")
                }
                if (target == null) androidx.compose.material3.OutlinedTextField(
                    newName, { newName = it }, singleLine = true, label = { Text("Název nové lokality") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Bod jde i s měřeními a soubory (.log, .ubx). Při přesunu zůstane v náčrtech objektů původní lokality " +
                        "nezměřený bod se stejným jménem. Shodné jméno v cílové lokalitě dostane příponu (2).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = target
                if (t != null) onConfirm(t, copy)
                else scope.launch {
                    val id = withContext(Dispatchers.IO) { c.db.jobs().insert(cz.antonin.rovergnss.data.Job(name = newName.trim())) }
                    onConfirm(id, copy)
                }
            }, enabled = target != null || newName.isNotBlank()) { Text(if (copy) "Kopírovat" else "Přesunout") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

fun fmtDist(d: Double): String = if (d < 1000) "${fmt(d, if (d < 10) 2 else 1)} m" else "${fmt(d / 1000, 2)} km"

fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}
