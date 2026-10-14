package cz.antonin.rovergnss.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.PhoneSample
import cz.antonin.rovergnss.data.PhoneSession
import cz.antonin.rovergnss.data.d2
import cz.antonin.rovergnss.link.Quality
import cz.antonin.rovergnss.phone.CompareStats
import cz.antonin.rovergnss.phone.PhoneCompare
import cz.antonin.rovergnss.phone.PhoneSource
import cz.antonin.rovergnss.phone.PhoneTtff
import cz.antonin.rovergnss.phone.TtffResult
import cz.antonin.rovergnss.phone.TtffVariant
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

fun sourceColor(s: PhoneSource): Color = Color(s.color)

/** Karta Telefon: porovnání GNSS telefonu s roverem, satelity telefonu, test doby do fixu, asistence X20. */
@Composable
fun PhoneScreen(c: RoverController) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("Porovnání") })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("Satelity") })
            Tab(selected = sub == 2, onClick = { sub = 2 }, text = { Text("Start") })
            Tab(selected = sub == 3, onClick = { sub = 3 }, text = { Text("X20") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (sub) {
                0 -> CompareTab(c)
                1 -> SatScreen(c, phoneFirst = true)
                2 -> TtffTab(c)
                else -> AssistTab(c)
            }
        }
    }
}

// ====================================================================== Porovnání
@Composable
private fun CompareTab(c: RoverController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by c.phone.info.collectAsStateWithLifecycle()
    val enabled by c.phone.enabled.collectAsStateWithLifecycle()
    val live by c.phoneCompare.live.collectAsStateWithLifecycle()
    val history by c.phoneCompare.history.collectAsStateWithLifecycle()
    val stats by c.phoneCompare.stats.collectAsStateWithLifecycle()
    val unpaired by c.phoneCompare.unpaired.collectAsStateWithLifecycle()
    val session by c.phoneCompare.session.collectAsStateWithLifecycle()
    val pos by c.pos.collectAsStateWithLifecycle()
    val job by c.activeJob.collectAsStateWithLifecycle()
    val sessionsFlow = remember(job?.id) { job?.let { c.db.phone().sessionsByJob(it.id) } ?: emptyFlow<List<PhoneSession>>() }
    val sessions by sessionsFlow.collectAsState(initial = emptyList())
    var pendingExport by remember { mutableStateOf<PhoneSession?>(null) }   // null + exportCurrent = aktuální
    var exportCurrent by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<PhoneSession?>(null) }
    var onMap by remember { mutableStateOf(c.prefs.phoneOnMap) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        c.phonePermissionChanged()
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        val s = pendingExport
        val cur = exportCurrent
        pendingExport = null
        exportCurrent = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    val list = if (cur || s == null) c.phoneCompare.currentSamples() else c.db.phone().samples(s.id)
                    context.contentResolver.openOutputStream(uri)?.use { PhoneCompare.csv(list, it) } ?: -1
                } catch (e: java.io.IOException) {
                    -1
                }
            }
            c.postMessage(if (n < 0) "Soubor nelze zapsat" else "Exportováno $n poloh")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section("Zdroje telefonu") {
            if (!info.permission) {
                Text("Pro polohu telefonu je potřeba oprávnění k přesné poloze.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Povolit polohu") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhoneSource.entries.forEach { s ->
                    FilterChip(
                        selected = s in enabled,
                        onClick = { c.setPhoneSource(s, s !in enabled) },
                        label = { Text(s.label) },
                        leadingIcon = { Dot(sourceColor(s)) },
                        enabled = s != PhoneSource.FUSED || info.gms,
                    )
                }
            }
            Text(
                "GNSS čip = čistá poloha z čipu (A-GNSS data už využívá). Fused = GNSS + Wi-Fi + BTS + senzory, jak polohu " +
                    "vidí běžné aplikace. Network = jen Wi-Fi a BTS.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!info.gms) Text("Fused: Google Play služby nejsou k dispozici.", style = MaterialTheme.typography.bodySmall)
            info.hardware?.let { KeyValue("GNSS čip", it, mono = false) }
            KeyValue("Surová měření (RTKLIB)", info.measurements, mono = false)
            info.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }

        Section("Reference – rover") {
            val p = pos
            if (p == null || !p.hasFix) {
                Text("Rover bez fixu nebo nepřipojen – polohy telefonu se nepárují.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityBadge(p.quality)
                    Text("hAcc ${fmt(p.hAcc, 3, " m")}" + if (p.itrf) " · ITRF → ETRS89" else " · ETRS89 (RTCM)",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (p.quality != Quality.FIX && p.quality != Quality.FLOAT && p.quality != Quality.HAS) {
                    Text("Reference je jen ${p.quality.label} – pro hodnocení telefonu je vhodné RTK nebo zkonvergovaný HAS.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (unpaired > 0) Text("Nespárováno poloh telefonu: $unpaired", style = MaterialTheme.typography.bodySmall)
            OffsetEditor(c)
        }

        PhoneSource.entries.filter { it in enabled }.forEach { s ->
            LiveCard(s, live[s], stats[s])
        }

        if (history.values.any { it.isNotEmpty() }) {
            Section("Rozptyl (střed = anténa roveru)") {
                Scatter(history, live, Modifier.fillMaxWidth().aspectRatio(1f))
            }
            Section("2D chyba v čase (10 min)") {
                ErrorChart(history, Modifier.fillMaxWidth().height(180.dp))
            }
        }

        Section("Statistika") {
            if (stats.isEmpty()) Text("Zatím žádné spárované polohy.", style = MaterialTheme.typography.bodySmall)
            PhoneSource.entries.forEach { s -> stats[s]?.let { StatsBlock(s, it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { c.phoneCompare.reset() }, enabled = session == null) { Text("Vynulovat") }
                OutlinedButton(onClick = {
                    exportCurrent = true
                    csvLauncher.launch("telefon_${stamp()}.csv")
                }, enabled = stats.isNotEmpty()) { Text("Export CSV") }
            }
        }

        Section("Uložení k zakázce ${job?.name ?: ""}") {
            val s = session
            if (s == null) {
                Button(onClick = {
                    job?.let { j -> c.phoneCompare.startSession(j.id, "Porovnání ${stamp(true)}", enabled) }
                }, enabled = job != null && enabled.isNotEmpty()) { Text("Začít ukládat") }
                Text("Ukládání vynuluje statistiku a ukládá každou spárovanou polohu.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Ukládá se: ${s.name} (${s.sources})", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { c.phoneCompare.stopSession() }) { Text("Ukončit ukládání") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(onMap, {
                    onMap = it
                    c.prefs.phoneOnMap = it
                })
                Text("stopy telefonu na mapě v Živě")
            }
            sessions.forEach { ss ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(ss.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${ss.sources} · ${if (ss.endMs == null) "ukládá se" else "${ss.samples} poloh"} · " +
                            "odsazení ${fmt(ss.offN * 100, 0)}/${fmt(ss.offE * 100, 0)}/${fmt(ss.offU * 100, 0)} cm",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            pendingExport = ss
                            csvLauncher.launch("${ss.name.replace(Regex("[^A-Za-z0-9_-]+"), "_")}.csv")
                        }, enabled = ss.endMs != null) { Text("Export CSV") }
                        TextButton(onClick = { toDelete = ss }, enabled = ss.endMs != null) { Text("Smazat") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    toDelete?.let { ss ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Smazat porovnání?") },
            text = { Text(ss.name) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { c.db.phone().deleteSession(ss.id) }
                    toDelete = null
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Zrušit") } },
        )
    }
}

private fun stamp(human: Boolean = false): String =
    SimpleDateFormat(if (human) "yyyy-MM-dd HH:mm" else "yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

/** Odsazení telefonu od antény roveru (cm): telefon = anténa + odsazení. */
@Composable
private fun OffsetEditor(c: RoverController) {
    fun txt(m: Double) = String.format(Locale.US, "%.0f", m * 100)
    var n by remember { mutableStateOf(txt(c.prefs.phoneOffN)) }
    var e by remember { mutableStateOf(txt(c.prefs.phoneOffE)) }
    var u by remember { mutableStateOf(txt(c.prefs.phoneOffU)) }
    fun parse(s: String): Double? = s.replace(',', '.').toDoubleOrNull()?.takeIf { abs(it) <= 10_000 }?.div(100)
    Text("Odsazení telefonu od antény [cm] (sever, východ, nahoru)", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OffField("ΔN", n, parse(n) == null, Modifier.weight(1f)) { v ->
            n = v
            parse(v)?.let { c.prefs.phoneOffN = it }
        }
        OffField("ΔE", e, parse(e) == null, Modifier.weight(1f)) { v ->
            e = v
            parse(v)?.let { c.prefs.phoneOffE = it }
        }
        OffField("ΔU", u, parse(u) == null, Modifier.weight(1f)) { v ->
            u = v
            parse(v)?.let { c.prefs.phoneOffU = it }
        }
    }
    TextButton(onClick = {
        n = "0"; e = "0"; u = "0"
        c.prefs.phoneOffN = 0.0
        c.prefs.phoneOffE = 0.0
        c.prefs.phoneOffU = 0.0
    }) { Text("Telefon u antény (0)") }
}

@Composable
private fun OffField(label: String, value: String, error: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, isError = error,
        modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun LiveCard(s: PhoneSource, x: PhoneSample?, st: CompareStats?) {
    Section(s.label) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(sourceColor(s))
            Text(if (x == null) "čekám na spárovanou polohu…" else "n = ${st?.n ?: 0}", style = MaterialTheme.typography.bodySmall)
        }
        if (x == null) return@Section
        KeyValue("ΔN / ΔE / ΔU", "${fmt(x.dN, 2)} / ${fmt(x.dE, 2)} / ${fmt(x.dU, 2)} m")
        KeyValue("2D chyba", fmt(x.d2, 2, " m"))
        KeyValue("Hlášená přesnost", "${fmt(x.acc, 1, " m")} / ${fmt(x.vAcc, 1, " m")}")
        x.acc?.takeIf { it > 0 }?.let { a ->
            val r = x.d2 / a
            KeyValue("Chyba / přesnost", fmt(r, 2), valueColor = if (r > 1) MaterialTheme.colorScheme.error else null)
        }
        KeyValue("Stáří polohy", "${x.ageMs} ms")
        x.numSv?.let { KeyValue("SV v řešení", it.toString()) }
        KeyValue("Reference", Quality.of(x.refQuality).label + if (x.refRtcm) " (RTCM)" else "")
    }
}

@Composable
private fun StatsBlock(s: PhoneSource, st: CompareStats) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(sourceColor(s))
        Text("${s.label} – ${st.n} poloh", style = MaterialTheme.typography.titleSmall)
    }
    KeyValue("Průměr ΔN / ΔE / ΔU", "${fmt(st.meanN, 2)} / ${fmt(st.meanE, 2)} / ${fmt(st.meanU, 2)} m")
    KeyValue("σ N / E / U", "${fmt(st.sN, 2)} / ${fmt(st.sE, 2)} / ${fmt(st.sU, 2)} m")
    KeyValue("RMS 2D", fmt(st.rms2d, 2, " m"))
    KeyValue("CEP50 / CEP95", "${fmt(st.cep50, 2)} / ${fmt(st.cep95, 2)} m")
    KeyValue("Max 2D", fmt(st.max2d, 2, " m"))
    KeyValue("Průměrná hlášená přesnost", fmt(st.meanAcc, 1, " m"))
    st.overAccPct?.let { KeyValue("Chyba > hlášená přesnost", fmt(it, 0, " %")) }
}

/** Pěkné kolo 1–2–5×10ⁿ ≥ v. */
private fun niceCeil(v: Double): Double {
    if (v <= 0 || v.isNaN()) return 1.0
    val p = 10.0.pow(floor(log10(v)))
    for (k in listOf(1.0, 2.0, 5.0, 10.0)) if (k * p >= v) return k * p
    return 10 * p
}

private fun scaleLabel(m: Double): String = when {
    m >= 1 -> "${fmt(m, if (m < 10 && m % 1.0 != 0.0) 1 else 0)} m"
    else -> "${fmt(m * 100, 0)} cm"
}

/** Graf rozptylu: střed = rover, osy N/E, kruhy hlášené přesnosti posledních poloh. */
@Composable
private fun Scatter(history: Map<PhoneSource, List<PhoneSample>>, live: Map<PhoneSource, PhoneSample>, modifier: Modifier) {
    val all = history.values.flatten()
    val d2 = all.map { it.d2 }.sorted()
    val p95 = if (d2.isEmpty()) 1.0 else d2[((d2.size - 1) * 0.95).toInt()]
    val range = niceCeil(max(p95, live.values.mapNotNull { it.acc }.maxOrNull() ?: 0.0) * 1.1)
    val grid = MaterialTheme.colorScheme.outline
    Column {
        Canvas(modifier.padding(4.dp)) {
            val r = size.minDimension / 2
            val ctr = Offset(size.width / 2, size.height / 2)
            val k = r / range.toFloat()
            for (i in 1..4) drawCircle(grid, radius = r * i / 4, center = ctr, style = Stroke(1f))
            drawLine(grid, Offset(ctr.x - r, ctr.y), Offset(ctr.x + r, ctr.y))
            drawLine(grid, Offset(ctr.x, ctr.y - r), Offset(ctr.x, ctr.y + r))
            for ((s, l) in history) {
                val col = sourceColor(s)
                for (x in l) {
                    val px = Offset(ctr.x + (x.dE * k).toFloat(), ctr.y - (x.dN * k).toFloat())
                    if (px.x < 0 || px.y < 0 || px.x > size.width || px.y > size.height) continue
                    drawCircle(col.copy(alpha = 0.45f), radius = 2.5.dp.toPx(), center = px)
                }
            }
            for ((s, x) in live) {
                val col = sourceColor(s)
                val px = Offset(ctr.x + (x.dE * k).toFloat(), ctr.y - (x.dN * k).toFloat())
                drawCircle(col, radius = 5.dp.toPx(), center = px)
                x.acc?.let { a -> drawCircle(col, radius = (a * k).toFloat(), center = px, style = Stroke(2.dp.toPx())) }
            }
            drawCircle(Color.Black, radius = 4.dp.toPx(), center = ctr)
        }
        Text(
            "Kruh mřížky = ${scaleLabel(range / 4)}, okraj ${scaleLabel(range)}. Sever nahoře. Velký kruh = hlášená přesnost.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 2D chyba zdrojů v čase (posledních 10 min). */
@Composable
private fun ErrorChart(history: Map<PhoneSource, List<PhoneSample>>, modifier: Modifier) {
    val all = history.values.flatten()
    if (all.isEmpty()) return
    val t1 = all.maxOf { it.timeMs }
    val t0 = t1 - 600_000
    val vals = all.filter { it.timeMs >= t0 }.map { it.d2 }.sorted()
    val yMax = niceCeil(if (vals.isEmpty()) 1.0 else vals[((vals.size - 1) * 0.98).toInt()] * 1.1)
    val grid = MaterialTheme.colorScheme.outline
    Column {
        Canvas(modifier.padding(vertical = 4.dp)) {
            for (i in 0..4) {
                val y = size.height * i / 4
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            for ((s, l) in history) {
                val pts = l.filter { it.timeMs >= t0 }
                if (pts.size < 2) continue
                val path = Path()
                pts.forEachIndexed { i, x ->
                    val px = ((x.timeMs - t0).toFloat() / 600_000f) * size.width
                    val py = size.height - (x.d2 / yMax).toFloat().coerceIn(0f, 1f) * size.height
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, sourceColor(s), style = Stroke(2.dp.toPx()))
            }
        }
        Text("Svisle 0 – ${scaleLabel(yMax)} (mřížka ${scaleLabel(yMax / 4)}), vodorovně 10 min do teď.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ====================================================================== Test doby do fixu
private val TTFF_REPEATS = listOf(1, 3, 5, 10)
private val TTFF_LIMIT = listOf(60, 120, 300, 600)
private val TTFF_PAUSE = listOf(10, 30, 60)

@Composable
private fun TtffTab(c: RoverController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by c.phoneTtff.state.collectAsStateWithLifecycle()
    val results by c.phoneTtff.results.collectAsStateWithLifecycle()
    val info by c.phone.info.collectAsStateWithLifecycle()
    val pos by c.pos.collectAsStateWithLifecycle()
    var variant by rememberSaveable { mutableStateOf(TtffVariant.COLD_AGNSS) }
    var repeats by rememberSaveable { mutableIntStateOf(3) }
    var limit by rememberSaveable { mutableIntStateOf(120) }
    var pause by rememberSaveable { mutableIntStateOf(30) }
    var online by remember { mutableStateOf(c.phoneTtff.online()) }
    var airplane by remember { mutableStateOf(c.phoneTtff.airplane()) }
    LaunchedEffect(Unit) {
        while (true) {
            online = c.phoneTtff.online()
            airplane = c.phoneTtff.airplane()
            delay(2000)
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { PhoneTtff.csv(results, it) } ?: -1
                } catch (e: java.io.IOException) {
                    -1
                }
            }
            c.postMessage(if (n < 0) "Soubor nelze zapsat" else "Exportováno $n pokusů")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section("Test doby do fixu telefonu") {
            if (!info.permission) Text("Nejdřív povol polohu (záložka Porovnání).", color = MaterialTheme.colorScheme.error)
            Text(
                "Příjem GNSS se vypne, podle varianty se smažou nebo stáhnou asistenční data čipu a měří se čas do první polohy " +
                    "a do přesnosti < 10 m a < 5 m vůči roveru (potřebuje fix roveru). Podpora příkazů závisí na výrobci telefonu.",
                style = MaterialTheme.typography.bodySmall,
            )
            KeyValue("Internet", if (online) "ano" else "ne", mono = false)
            KeyValue("Režim letadlo", if (airplane) "ano" else "ne", mono = false)
            KeyValue("Rover", pos?.takeIf { it.hasFix }?.quality?.label ?: "bez fixu – jen čas do první polohy", mono = false)
            Text("Varianta", style = MaterialTheme.typography.titleSmall)
            ChipRow(TtffVariant.entries.map { it to it.label }, variant) { variant = it }
            if (variant == TtffVariant.COLD_OFFLINE) Text(
                "Zapni režim letadlo a Bluetooth nech zapnutý (rover zůstane připojený).",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Opakování", style = MaterialTheme.typography.titleSmall)
            ChipRow(TTFF_REPEATS.map { it to "$it×" }, repeats) { repeats = it }
            Text("Limit pokusu", style = MaterialTheme.typography.titleSmall)
            ChipRow(TTFF_LIMIT.map { it to if (it < 120) "$it s" else "${it / 60} min" }, limit) { limit = it }
            Text("Pauza mezi pokusy", style = MaterialTheme.typography.titleSmall)
            ChipRow(TTFF_PAUSE.map { it to "$it s" }, pause) { pause = it }
            if (st.running) {
                Text("Pokus ${st.attempt}/${st.attempts} (${st.variant?.label}) – ${st.phase} ${fmt(st.elapsed, 0, " s")}",
                    style = MaterialTheme.typography.bodyMedium)
                st.current?.let { r ->
                    KeyValue("První poloha", fmt(r.firstFix, 1, " s"))
                    KeyValue("< 10 m / < 5 m", "${fmt(r.to10, 1)} / ${fmt(r.to5, 1)} s")
                    if (r.fusedFirst != null) KeyValue("Fused první / < 10 m", "${fmt(r.fusedFirst, 1)} / ${fmt(r.fusedTo10, 1)} s")
                    KeyValue("SV max", r.svMax.toString())
                }
                Button(onClick = { c.phoneTtff.stop() }) { Text("Zastavit") }
            } else {
                Button(onClick = { c.phoneTtff.start(variant, repeats, limit, pause) }, enabled = info.permission) { Text("Spustit test") }
            }
        }

        if (results.isNotEmpty()) {
            Section("Výsledky") {
                TtffVariant.entries.forEach { v ->
                    val l = results.filter { it.variant == v }
                    if (l.isEmpty()) return@forEach
                    Text("${v.label} – ${l.size} pokusů", style = MaterialTheme.typography.titleSmall)
                    SummaryRow("první poloha", l.mapNotNull { it.firstFix })
                    SummaryRow("< 10 m", l.mapNotNull { it.to10 })
                    SummaryRow("< 5 m", l.mapNotNull { it.to5 })
                    if (l.any { it.fusedFirst != null }) SummaryRow("Fused první", l.mapNotNull { it.fusedFirst })
                }
                Text("min / medián / max [s]", style = MaterialTheme.typography.bodySmall)
                Text("Pokusy", style = MaterialTheme.typography.titleSmall)
                results.forEach { r -> ResultRow(r) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { csvLauncher.launch("ttff_${stamp()}.csv") }) { Text("Export CSV") }
                    OutlinedButton(onClick = { c.phoneTtff.clear() }, enabled = !st.running) { Text("Smazat") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SummaryRow(label: String, v: List<Double>) {
    val s = PhoneTtff.summary(v)
    KeyValue(label, if (s == null) "–" else "${fmt(s.first, 1)} / ${fmt(s.second, 1)} / ${fmt(s.third, 1)}")
}

@Composable
private fun ResultRow(r: TtffResult) {
    fun b(v: Boolean?) = when (v) { null -> "–"; true -> "ano"; false -> "ne" }
    Text(
        "${r.n}. ${r.variant.label}: fix ${fmt(r.firstFix, 1)} s, <10 m ${fmt(r.to10, 1)} s, <5 m ${fmt(r.to5, 1)} s" +
            (if (r.fusedFirst != null) ", Fused ${fmt(r.fusedFirst, 1)} s" else "") +
            ", SV ${r.svAtFix ?: "–"}/${r.svMax}" + (if (r.timedOut) ", limit" else "") +
            " · smazání ${b(r.deleteOk)}" + (if (r.variant == TtffVariant.COLD_AGNSS) ", stažení ${b(r.injectOk)}" else ""),
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun <T> ChipRow(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (v, label) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) }) }
    }
}

// ====================================================================== Asistence X20
@Composable
private fun AssistTab(c: RoverController) {
    val a by c.assist.state.collectAsStateWithLifecycle()
    var ini by remember { mutableStateOf(c.prefs.assistIni) }
    var dbd by remember { mutableStateOf(c.prefs.assistDbd) }
    val df = remember { SimpleDateFormat("d. M. HH:mm:ss", Locale.getDefault()) }
    fun t(ms: Long) = if (ms <= 0) "–" else df.format(Date(ms))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section("Asistence X20 z telefonu") {
            Text(
                "Kratší studený start X20 bez internetu: po připojení bez fixu se do X20 pošle čas a přibližná poloha " +
                    "(UBX-MGA-INI) a uložená navigační databáze (UBX-MGA-DBD). Vyžaduje firmware roveru 0.11.0+.",
                style = MaterialTheme.typography.bodySmall,
            )
            KeyValue(
                "Rover", when (a.fwOk) {
                    null -> "nepřipojen"
                    true -> "podporuje (FW 0.11.0+)"
                    false -> "starý firmware – nahraj 0.11.0+"
                }, mono = false,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ini, {
                    ini = it
                    c.prefs.assistIni = it
                })
                Text("po připojení bez fixu poslat čas a polohu")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(dbd, {
                    dbd = it
                    c.prefs.assistDbd = it
                })
                Text("zálohovat databázi X20 (každých 30 min při fixu) a vracet ji")
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { c.assist.inject() }, enabled = a.fwOk == true) { Text("Poslat teď") }
                OutlinedButton(onClick = { c.assist.backup() }, enabled = a.fwOk == true && !a.backupRunning) {
                    Text(if (a.backupRunning) "Zálohuji…" else "Zálohovat teď")
                }
                OutlinedButton(onClick = { c.assist.deleteBackup() }, enabled = a.lastBackupMs > 0) { Text("Smazat zálohu") }
            }
        }
        Section("Stav") {
            KeyValue("Poslední odeslání", t(a.lastIniMs), mono = false)
            a.lastIni?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            KeyValue("Záloha databáze", t(a.lastBackupMs), mono = false)
            a.lastBackup?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (a.lastRestoreMs > 0) KeyValue("Databáze vrácena", "${t(a.lastRestoreMs)} (${a.restoredFrames} zpráv)", mono = false)
            KeyValue("MGA-ACK přijato / odmítnuto", "${a.ackAccepted ?: "–"} / ${a.ackRejected ?: "–"}")
            KeyValue(
                "Potvrzování (ACKAIDING)", when (a.ackAiding) {
                    1 -> "zapnuto"
                    0 -> "X20 nepodporuje"
                    -1 -> "bez odpovědi"
                    else -> "–"
                }, mono = false,
            )
            KeyValue("Rámce UBX z telefonu OK / vadné", "${a.ubxInOk ?: "–"} / ${a.ubxInBad ?: "–"}")
        }
        Section("Ověření přínosu") {
            Text(
                "Studený start X20 a doba do fixu: porovnat bez asistence / jen čas a poloha / i s databází " +
                    "(test TTFF ve webu roveru, nebo restart zde a sledovat Živě).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { c.resetX20("cold") }, enabled = a.fwOk != null) { Text("Studený start X20") }
                OutlinedButton(onClick = {
                    c.resetX20("cold")
                    c.assist.inject()
                }, enabled = a.fwOk == true) { Text("Studený start + asistence") }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
