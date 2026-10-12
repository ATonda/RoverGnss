package cz.antonin.rovergnss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cz.antonin.rovergnss.service.LinkQuality

private fun barColor(bars: Int): Color = when (bars) {
    4 -> Color(0xFF2E7D32)
    3 -> Color(0xFF7CB342)
    2 -> Color(0xFFEF6C00)
    1 -> Color(0xFFC62828)
    else -> Color(0xFF9E9E9E)
}

/** Sloupečky kvality spojení (0–4). */
@Composable
fun LinkBars(q: LinkQuality, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(5.dp)
                    .height((4 + i * 4).dp)
                    .background(if (i <= q.bars) barColor(q.bars) else Color(0xFFE0E0E0), RoundedCornerShape(1.dp))
            )
        }
    }
}

/** Krátký popis: zpoždění, ztráty, fronta v roveru. */
fun linkText(q: LinkQuality): String {
    if (q.ageMs == null) return "BT: žádná data"
    val parts = ArrayList<String>()
    if (q.ageMs > 3000) parts += "data před ${fmt(q.ageMs / 1000.0, 0)} s"
    q.latencyMs?.let { parts += "zpoždění ${fmt(it / 1000.0, 1)} s" }
    q.lossPct?.let { parts += "ztráty ${fmt(it, 0)} %" }
    q.backlog?.let { if (it > 0) parts += "fronta ${fmt(it / 1024.0, 1)} kB" }
    return "BT: " + parts.joinToString(", ")
}

/** Řádek: sloupečky + popis. */
@Composable
fun LinkMeter(q: LinkQuality, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkBars(q)
        Text(linkText(q), style = MaterialTheme.typography.bodySmall)
    }
}
