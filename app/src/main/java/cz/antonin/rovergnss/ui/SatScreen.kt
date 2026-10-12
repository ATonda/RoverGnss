package cz.antonin.rovergnss.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.link.Sat
import cz.antonin.rovergnss.service.RoverController
import kotlin.math.cos
import kotlin.math.sin

fun systemColor(gnssId: Int): Color = when (gnssId) {
    0 -> Color(0xFF1565C0)  // GPS
    2 -> Color(0xFF2E7D32)  // Galileo
    3 -> Color(0xFFC62828)  // BeiDou
    6 -> Color(0xFF6A1B9A)  // GLONASS
    5 -> Color(0xFF00838F)  // QZSS
    1 -> Color(0xFF757575)  // SBAS
    else -> Color(0xFF795548)
}

private val SYSTEM_NAMES = mapOf(0 to "GPS", 2 to "Galileo", 3 to "BeiDou", 6 to "GLONASS", 5 to "QZSS", 1 to "SBAS", 7 to "NavIC")

@Composable
fun SatScreen(c: RoverController) {
    val sats by c.sats.collectAsStateWithLifecycle()
    val sorted = sats.sortedWith(compareBy<Sat>({ it.gnssId }, { it.svId }))

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Obloha") {
                Skyplot(sats, Modifier.fillMaxWidth().aspectRatio(1f))
                val bySys = sats.groupBy { it.gnssId }
                Text(
                    bySys.entries.sortedBy { it.key }.joinToString("  ") { (id, l) ->
                        "${SYSTEM_NAMES[id] ?: "?"} ${l.count { it.used }}/${l.size}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Použité ${sats.count { it.used }} / sledované ${sats.size}. Plný kruh = v řešení.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(sorted, key = { it.gnssId * 1000 + it.svId }) { s -> SatRow(s) }
    }
}

@Composable
private fun SatRow(s: Sat) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${s.system}${s.svId.toString().padStart(2, '0')}", fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp))
        Text("${s.elev}°", fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp))
        Box(Modifier.weight(1f).height(14.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * (s.cno.coerceIn(0, 55) / 55f)
                drawRect(Color(0xFFE0E0E0), size = size)
                drawRect(
                    if (s.used) systemColor(s.gnssId) else systemColor(s.gnssId).copy(alpha = 0.35f),
                    size = Size(w, size.height),
                )
            }
        }
        Text("${s.cno}", fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp).width(28.dp))
    }
}

@Composable
private fun Skyplot(sats: List<Sat>, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.outline
    Canvas(modifier.padding(8.dp)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        for (e in listOf(0, 30, 60)) drawCircle(grid, radius = r * (90 - e) / 90f, center = c, style = Stroke(1.5f))
        drawLine(grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        for (s in sats) {
            if (s.elev < 0) continue
            val rr = r * (90 - s.elev) / 90f
            val a = Math.toRadians(s.azim.toDouble())
            val p = Offset(c.x + rr * sin(a).toFloat(), c.y - rr * cos(a).toFloat())
            val col = systemColor(s.gnssId)
            if (s.used) drawCircle(col, radius = 9.dp.toPx(), center = p)
            else drawCircle(col, radius = 9.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
        }
    }
}
