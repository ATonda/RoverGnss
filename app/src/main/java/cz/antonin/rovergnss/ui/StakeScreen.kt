package cz.antonin.rovergnss.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import cz.antonin.rovergnss.export.Exporter
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vytyčování: výběr bodů, pořadí (podle názvu / nejbližší další), spuštění navigace po seznamu,
 * stav vytyčení u bodů a protokol CSV.
 */
@Composable
fun StakeScreen(c: RoverController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by c.activeJob.collectAsStateWithLifecycle()
    val pointsFlow = remember(job?.id) { job?.let { c.db.points().byJob(it.id) } ?: emptyFlow() }
    val all by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val points = remember(all) { all.filter { it.lat != null && it.lon != null }.sortedWith(RoverController.NaturalOrder) }
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

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = order == 0, onClick = { order = 0 }, label = { Text("podle názvu") })
                FilterChip(selected = order == 1, onClick = { order = 1 }, label = { Text("nejbližší další") })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stake == null) Button(onClick = { c.startStake(todo, order) }, enabled = todo.isNotEmpty()) {
                    Text("Vytyčovat (${todo.size})")
                } else OutlinedButton(onClick = { c.stopStake() }) { Text("Ukončit vytyčování") }
                OutlinedButton(onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    protoLauncher.launch("RG_${stamp}_vytyceni.csv")
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
        if (points.isEmpty()) Text("Lokalita nemá body se souřadnicemi.", modifier = Modifier.padding(16.dp))
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
                    Column(Modifier.weight(1f)) {
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
}
