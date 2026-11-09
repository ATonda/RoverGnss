package cz.antonin.rovergnss.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.data.PointIo
import cz.antonin.rovergnss.data.PointSource
import cz.antonin.rovergnss.export.Exporter
import cz.antonin.rovergnss.export.FileNames
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vytyčování: zadané body a body ČÚZK (0.13.0 – změřené body REC jsou v záložce Body). Nový bod, import a export
 * CSV / KML, karta bodu (formulář, měření daného bodu), výběr bodů, pořadí (podle názvu / nejbližší další),
 * spuštění navigace po seznamu, stav vytyčení u bodů a protokol CSV.
 */
@Composable
fun StakeScreen(c: RoverController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by c.activeJob.collectAsStateWithLifecycle()
    val pointsFlow = remember(job?.id) { job?.let { c.db.points().byJob(it.id) } ?: emptyFlow() }
    val all by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // dané body (ruční, import, ČÚZK); změřené body REC se tu nevytyčují
    val given = remember(all) { all.filter { it.source != PointSource.MEASURED }.sortedWith(RoverController.NaturalOrder) }
    val points = remember(given) { given.filter { it.lat != null && it.lon != null } }
    var editing by remember { mutableStateOf<Point?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    val stake by c.stake.collectAsStateWithLifecycle()
    val nav by c.navTarget.collectAsStateWithLifecycle()
    var order by remember { mutableIntStateOf(c.prefs.stakeOrder) }
    val sel = points.filter { it.stakeSel }
    val todo = sel.filter { it.stakeMs == null }
    val df = remember { SimpleDateFormat("d. M. HH:mm", Locale.getDefault()) }

    val protoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { Exporter.stakeCsv(sel.ifEmpty { points }, it) } ?: -1
                } catch (e: Exception) {
                    -1
                }
            }
            c.postMessage(if (n >= 0) "Protokol: $n bodů" else "Soubor nelze zapsat")
        }
    }

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
                    context.contentResolver.openOutputStream(uri)?.use { PointIo.export(given, it) } ?: -1
                } catch (e: Exception) {
                    -1
                }
            }
            c.postMessage(if (n >= 0) "Exportováno $n bodů" else "Soubor nelze zapsat")
        }
    }
    val kmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { Exporter.pointsKml(job?.name ?: "Body", given, it) } ?: -1
                } catch (e: Exception) {
                    -1
                }
            }
            c.postMessage(if (n >= 0) "Exportováno $n bodů (KML)" else "Soubor nelze zapsat")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    scope.launch {
                        val j = c.ensureJob()
                        editing = Point(jobId = j.id, name = "")
                    }
                }) { Text("Nový bod") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import") }
                Box {
                    OutlinedButton(onClick = { exportMenu = true }, enabled = given.isNotEmpty()) { Text("Export") }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(text = { Text("CSV (S-JTSK, ETRS89, Bpv)") }, onClick = {
                            exportMenu = false
                            exportLauncher.launch(FileNames.job(job?.name ?: "lokalita", "dane_body", System.currentTimeMillis(), "csv"))
                        })
                        DropdownMenuItem(text = { Text("KML (Google Earth, mapy)") }, onClick = {
                            exportMenu = false
                            kmlLauncher.launch(FileNames.job(job?.name ?: "lokalita", "dane_body", System.currentTimeMillis(), "kml"))
                        })
                    }
                }
            }
            Text(
                "Zadané body a body ČÚZK (na mapě v Živě zapni vrstvu ČÚZK a klepni na bod). Klepnutím na bod karta s měřením.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = order == 0, onClick = { order = 0 }, label = { Text("podle názvu") })
                FilterChip(selected = order == 1, onClick = { order = 1 }, label = { Text("nejbližší další") })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stake == null) Button(onClick = { c.startStake(todo, order) }, enabled = todo.isNotEmpty()) {
                    Text("Vytyčovat (${todo.size})")
                } else OutlinedButton(onClick = { c.stopStake() }) { Text("Ukončit vytyčování") }
                OutlinedButton(onClick = {
                    protoLauncher.launch(
                        cz.antonin.rovergnss.export.FileNames.job(c.activeJob.value?.name ?: "lokalita", "vytyceni", System.currentTimeMillis(), "csv")
                    )
                }, enabled = points.any { it.stakeMs != null }) { Text("Protokol CSV") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { c.setStakeSelAll(points, true) }) { Text("Vybrat vše") }
                TextButton(onClick = { c.setStakeSelAll(points, false) }) { Text("Zrušit výběr") }
                TextButton(onClick = { c.clearStake(sel) }, enabled = sel.any { it.stakeMs != null }) { Text("Vytyčit znovu") }
            }
            Text(
                "Vybrané body ${sel.size}, vytyčeno ${sel.size - todo.size}. Navigace vede po nevytyčených bodech; " +
                    "„Vytyčeno“ v panelu navigace zprůměruje polohu a uloží odchylku.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()
        if (points.isEmpty()) Text("Lokalita nemá zadané body – přidej Nový bod, Import, nebo bod ČÚZK z mapy.", modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(points, key = { it.id }) { p ->
                val current = stake != null && nav?.id == p.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (current) Color(0xFFFFEBEE) else Color.Transparent)
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(p.stakeSel, { c.setStakeSel(p, it) })
                    Column(Modifier.weight(1f).clickable { editing = p }) {
                        Text(p.name + if (current) "  ← právě" else "", style = MaterialTheme.typography.titleSmall)
                        if (p.stakeMs != null) {
                            val d2 = if (p.stakeDN != null && p.stakeDE != null) kotlin.math.hypot(p.stakeDN, p.stakeDE) else null
                            Text(
                                "vytyčeno ${df.format(Date(p.stakeMs))}, 2D ${fmt(d2, 3)} m" +
                                    (p.stakeDH?.let { ", výška ${fmt(it, 3)} m" } ?: ""),
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32),
                            )
                        } else {
                            Text("nevytyčeno", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { c.navigateTo(p) }) { Text("Navigovat") }
                }
                HorizontalDivider()
            }
        }
    }

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
