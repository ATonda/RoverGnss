package cz.antonin.rovergnss.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.Measurement
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.export.Exporter
import cz.antonin.rovergnss.link.LinkState
import cz.antonin.rovergnss.link.Quality
import cz.antonin.rovergnss.service.MeasureState
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

private val DURATIONS = listOf(60 to "1 min", 180 to "3 min", 600 to "10 min", 1800 to "30 min", 0 to "ručně")

private fun signed(v: Double?, dec: Int) = if (v == null) "–" else (if (v >= 0) "+" else "") + fmt(v, dec)

private fun mmss(sec: Long) = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)

/** Délka měření [s]. */
private fun Measurement.durationSec(): Long = ((endMs ?: System.currentTimeMillis()) - startMs) / 1000

private fun qualityName(code: Int) = if (code == 0) "smíšená" else Quality.of(code).label

/**
 * Doplněk karty uloženého bodu: navigace k bodu, měření bodu (relace) a jejich historie s grafy.
 * Souřadnice bodu se měřením nemění – měření je kontrola přijímače proti daným souřadnicím.
 */
@Composable
fun PointExtras(c: RoverController, p: Point, onClose: () -> Unit) {
    if (p.id == 0L) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meas by c.measure.collectAsStateWithLifecycle()
    val link by c.linkState.collectAsStateWithLifecycle()
    val rec by c.recording.collectAsStateWithLifecycle()
    val listFlow = remember(p.id) { c.db.measurements().byPoint(p.id) }
    val list by listFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var sec by remember { mutableIntStateOf(c.prefs.measureSec) }
    var toDelete by remember { mutableStateOf<Measurement?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    var ubxPending by remember { mutableStateOf<Measurement?>(null) }
    val ubxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val name = ubxPending?.rawUbx
        ubxPending = null
        if (uri == null || name == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        c.rawFile(name).inputStream().use { it.copyTo(out) }
                    } ?: -1L
                } catch (e: Exception) {
                    -1L
                }
            }
            c.postMessage(if (n >= 0) "Exportováno ${n / 1024} kB surových dat" else "Soubor nelze zapsat")
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { Exporter.measurementsCsv(mapOf(p.id to p), list, it) } ?: -1
                } catch (e: Exception) {
                    -1
                }
            }
            c.postMessage(if (n >= 0) "Exportováno $n měření" else "Soubor nelze zapsat")
        }
    }

    HorizontalDivider()
    OutlinedButton(onClick = {
        c.navigateTo(p)
        onClose()
    }, enabled = p.lat != null && p.lon != null) { Text("Navigovat k bodu") }

    Text("Měření bodu", style = MaterialTheme.typography.titleSmall)
    Text(
        "Postav anténu na značku bodu. Výška antény ${fmt(c.prefs.antennaH, 3)} m (nastavení v Trasách). " +
            "Měří se průměr epoch bez pohybu a porovná s danými souřadnicemi bodu; ty se nemění.",
        style = MaterialTheme.typography.bodySmall,
    )
    val running = meas
    if (running != null && running.point.id == p.id) {
        MeasureStatus(running, onStop = { c.stopMeasurement() }, onCancel = { c.stopMeasurement(save = false) })
    } else {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DURATIONS.forEach { (s, label) ->
                FilterChip(selected = sec == s, onClick = { sec = s }, label = { Text(label) })
            }
        }
        val reason = when {
            running != null -> "Probíhá měření bodu ${running.point.name}."
            rec != null -> "Běží záznam trasy – nejdřív ho ukonči."
            link !is LinkState.Connected -> "Rover není připojený."
            p.lat == null && p.y == null -> "Bod nemá souřadnice."
            else -> null
        }
        Button(onClick = {
            c.prefs.measureSec = sec
            c.startMeasurement(p, sec)
            onClose()
        }, enabled = reason == null) { Text("Měřit") }
        reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    val done = list.filter { it.endMs != null }
    if (done.isNotEmpty()) {
        Text("Historie měření (${done.size})", style = MaterialTheme.typography.titleSmall)
        MeasureCharts(done)
        done.asReversed().forEach { m ->
            MeasureRow(
                m, expanded == m.id, onClick = { expanded = if (expanded == m.id) null else m.id }, onDelete = { toDelete = m },
                onExportUbx = {
                    ubxPending = m
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(m.startMs))
                    ubxLauncher.launch("RG_${stamp}_${p.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.ubx")
                },
            )
        }
        OutlinedButton(onClick = {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safe = p.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
            exportLauncher.launch("RG_${stamp}_mereni_$safe.csv")
        }) { Text("Export měření CSV") }
    }

    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Smazat měření?") },
            text = { Text("Měření z ${SimpleDateFormat("d. M. yyyy HH:mm", Locale.getDefault()).format(Date(m.startMs))} i s jeho trasou. Nelze vrátit.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    c.deleteMeasurement(m)
                }) { Text("Smazat", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Zrušit") } },
        )
    }
}

/** Průběh měření: čas, epochy, rozptyl a odchylka od bodu. */
@Composable
fun MeasureStatus(m: MeasureState, onStop: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(m.measurementId) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val el = ((now - m.startMs) / 1000).coerceAtLeast(0)
    val r = m.result
    val o = m.offset
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xFFE3F2FD), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Měření ${m.point.name}  ${mmss(el)}" + if (m.plannedSec > 0) " / ${mmss(m.plannedSec.toLong())}" else "",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Zrušit") }
            Button(onClick = onStop) { Text("Ukončit") }
        }
        Text(
            (if (m.staticModel) "X20 statický model" else "model z profilu") +
                (if (m.raw) " · RAWX ${m.rawFrames} rámců" else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        if (r == null) {
            Text("Čekám na epochy…", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "epoch ${r.used}/${r.epochs}, ${qualityName(r.usedQuality)}" +
                    "  σ N/E/U ${fmt(r.sN, 3)}/${fmt(r.sE, 3)}/${fmt(r.sU, 3)} m",
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            )
            if (o != null) Text(
                "odchylka 2D ${fmt(o.d2, 3)} m (N ${signed(o.dN, 3)} E ${signed(o.dE, 3)})" +
                    ((o.dH ?: o.dU)?.let { "  výška ${signed(it, 3)}" } ?: ""),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            )
            if (r.epochs > r.used) Text(
                "Do průměru jen nejlepší kvalita (${qualityName(r.usedQuality)}); epochy v pohybu se nepočítají.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Pruh probíhajícího měření nad obsahem aplikace (na všech kartách). */
@Composable
fun MeasureBanner(c: RoverController) {
    val m by c.measure.collectAsStateWithLifecycle()
    m?.let { s ->
        MeasureStatus(
            s, onStop = { c.stopMeasurement() }, onCancel = { c.stopMeasurement(save = false) },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MeasureRow(m: Measurement, expanded: Boolean, onClick: () -> Unit, onDelete: () -> Unit, onExportUbx: () -> Unit) {
    val df = remember { SimpleDateFormat("d. M. yyyy HH:mm", Locale.getDefault()) }
    val d2 = if (m.dN != null && m.dE != null) hypot(m.dN, m.dE) else null
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(
            "${df.format(Date(m.startMs))} · ${mmss(m.durationSec())} · ${qualityName(m.usedQuality)} · ${m.corr}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "2D ${fmt(d2, 3)} m  N ${signed(m.dN, 3)}  E ${signed(m.dE, 3)}" +
                ((m.dH ?: m.dU)?.let { "  výška ${signed(it, 3)}" } ?: ""),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
        )
        if (expanded) {
            val t = MaterialTheme.typography.bodySmall
            if (m.dY != null && m.dX != null) Text("ΔY ${signed(m.dY, 3)}  ΔX ${signed(m.dX, 3)} m (S-JTSK)", fontFamily = FontFamily.Monospace, style = t)
            if (m.dH != null) Text("ΔH Bpv ${signed(m.dH, 3)} m (kvazigeoid CR-2005)", fontFamily = FontFamily.Monospace, style = t)
            Text("σ N/E/U ${fmt(m.sN, 3)} / ${fmt(m.sE, 3)} / ${fmt(m.sU, 3)} m", fontFamily = FontFamily.Monospace, style = t)
            Text(
                "epoch ${m.used} z ${m.epochs}: fix ${m.nFix}, float ${m.nFloat}, HAS ${m.nHas}, SBAS ${m.nDgnss}, 3D ${m.nOther}",
                style = t,
            )
            if (m.lat != null && m.lon != null) Text(
                "průměr ${dms(m.lat, true)}  ${dms(m.lon, false)}  h ${fmt(m.hEll, 3)} m",
                fontFamily = FontFamily.Monospace, style = t,
            )
            Text(
                "anténa ${fmt(m.antennaH, 3)} m · profil ${m.profile.ifEmpty { "–" }}" +
                    (if (m.staticModel) " · statický model" else ""),
                style = t,
            )
            if (m.rawUbx != null) TextButton(onClick = onExportUbx) { Text("Surová data .ubx (PPK)") }
            if (m.refDerived) Text("Dané ETRS89 bodu dopočtené ze S-JTSK (převod ČÚZK, ~1–2 cm) – odchylka obsahuje i chybu převodu.", style = t)
            TextButton(onClick = onDelete) { Text("Smazat měření", color = MaterialTheme.colorScheme.error) }
        }
        HorizontalDivider()
    }
}

/** Grafy historie: terč (odchylky sever/východ) a vývoj odchylky v čase. */
@Composable
private fun MeasureCharts(list: List<Measurement>) {
    val pts = list.filter { it.dN != null && it.dE != null }
    if (pts.isEmpty()) {
        Text("Bez odchylek – bod nemá dané ETRS89 ani S-JTSK.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val labelColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    // terč: střed = daný bod, sever nahoře
    val maxOff = max(pts.maxOf { max(abs(it.dN!!), abs(it.dE!!)) }, 0.01)
    val ring = niceStep(maxOff)
    Text("Odchylky od daného bodu (sever nahoře), kruhy po ${fmtM(ring)}", style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.size(220.dp).padding(4.dp)) {
        val cx = size.width / 2
        val cy = size.height / 2
        val rings = kotlin.math.ceil(maxOff / ring).toInt().coerceIn(1, 5)
        val scale = (size.minDimension / 2 - 8f) / (rings * ring).toFloat()
        for (k in 1..rings) drawCircle(gridColor, radius = (k * ring * scale).toFloat(), center = Offset(cx, cy), style = Stroke(1.5f))
        drawLine(gridColor, Offset(cx, 0f), Offset(cx, size.height), 1.5f)
        drawLine(gridColor, Offset(0f, cy), Offset(size.width, cy), 1.5f)
        text("N", cx + 6f, 18f, labelColor)
        pts.forEachIndexed { i, m ->
            val x = cx + (m.dE!! * scale).toFloat()
            val y = cy - (m.dN!! * scale).toFloat()
            val last = i == pts.lastIndex
            drawCircle(qualityColor(Quality.of(m.usedQuality)), radius = if (last) 9f else 6f, center = Offset(x, y))
            if (last) drawCircle(Color.Black, radius = 9f, center = Offset(x, y), style = Stroke(2f))
        }
    }
    // vývoj v čase: 2D (modře) a výška (oranžově), osa x = pořadí měření
    if (pts.size >= 2) {
        val d2 = pts.map { hypot(it.dN!!, it.dE!!) }
        val du = pts.map { it.dH ?: it.dU }
        val vals = d2 + du.filterNotNull()
        val lo = minOf(0.0, vals.min())
        val hi = max(vals.max(), lo + 0.01)
        Text("Vývoj odchylky: 2D modře, výška oranžově (od nejstaršího)", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(4.dp)) {
            val left = 64f
            val w = size.width - left - 8f
            val h = size.height - 16f
            fun yOf(v: Double) = (8f + h - ((v - lo) / (hi - lo) * h)).toFloat()
            fun xOf(i: Int) = left + w * i / (pts.size - 1)
            drawLine(gridColor, Offset(left, yOf(0.0)), Offset(left + w, yOf(0.0)), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            text(fmtM(hi), 2f, yOf(hi) + 10f, labelColor)
            text(fmtM(lo), 2f, yOf(lo), labelColor)
            series(d2.map { it }, ::xOf, ::yOf, Color(0xFF1565C0))
            series(du, ::xOf, ::yOf, Color(0xFFEF6C00))
        }
    }
}

private fun DrawScope.series(v: List<Double?>, xOf: (Int) -> Float, yOf: (Double) -> Float, color: Color) {
    var prev: Offset? = null
    v.forEachIndexed { i, d ->
        if (d == null) {
            prev = null
            return@forEachIndexed
        }
        val o = Offset(xOf(i), yOf(d))
        prev?.let { drawLine(color, it, o, 3f) }
        drawCircle(color, radius = 5f, center = o)
        prev = o
    }
}

private fun DrawScope.text(s: String, x: Float, y: Float, color: Color) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 26f
        this.color = android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
    }
    drawContext.canvas.nativeCanvas.drawText(s, x, y, paint)
}

/** Krok 1–2–5×10ⁿ m, aby se odchylky vešly do 1–5 kruhů. */
private fun niceStep(maxV: Double): Double {
    val raw = maxV / 3
    val p = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.first { it >= raw }
}

private fun fmtM(v: Double): String = when {
    abs(v) >= 1 -> fmt(v, 2) + " m"
    abs(v) >= 0.01 -> fmt(v * 100, 1) + " cm"
    else -> fmt(v * 1000, 0) + " mm"
}
