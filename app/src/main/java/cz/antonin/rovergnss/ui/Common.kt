package cz.antonin.rovergnss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.antonin.rovergnss.link.Quality
import java.util.Locale

fun qualityColor(q: Quality): Color = when (q) {
    Quality.FIX -> Color(0xFF2E7D32)
    Quality.FLOAT -> Color(0xFFEF6C00)
    Quality.HAS -> Color(0xFF1565C0)
    Quality.DGNSS -> Color(0xFF6A1B9A)
    Quality.GNSS -> Color(0xFFC62828)
    Quality.DR, Quality.NONE -> Color(0xFF757575)
}

fun qualityColorArgb(code: Int): Int = qualityColor(Quality.of(code)).let {
    android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
}

/** Číslo s pevným počtem desetinných míst, null -> pomlčka. */
fun fmt(v: Double?, dec: Int, unit: String = ""): String =
    if (v == null || v.isNaN()) "–" else String.format(Locale.US, "%.${dec}f", v) + unit

/** Úhel [°] jako 49°55′09,8368″ */
fun dms(deg: Double, latitude: Boolean): String {
    val h = if (latitude) (if (deg >= 0) "N" else "S") else (if (deg >= 0) "E" else "W")
    var a = kotlin.math.abs(deg)
    val d = a.toInt()
    a = (a - d) * 60
    val m = a.toInt()
    val s = (a - m) * 60
    return String.format(Locale.US, "%d°%02d′%07.4f″ %s", d, m, s, h).replace('.', ',')
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
fun QualityBadge(q: Quality, extra: String = "") {
    Row(
        Modifier
            .background(qualityColor(q), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            q.label + if (extra.isNotEmpty()) " $extra" else "",
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}
