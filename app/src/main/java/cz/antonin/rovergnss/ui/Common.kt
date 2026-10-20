package cz.antonin.rovergnss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/** Výška antény: uložená v m, zobrazená v cm (1 desetinné místo, desetinná čárka). */
fun antCm(m: Double): String = String.format(Locale.US, "%.1f cm", m * 100).replace('.', ',')

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

/** Baterie roveru; napětí mimo rozsah článku LiPo (2,5–4,4 V) = vadné měření, ne stav baterie. */
fun batteryText(s: cz.antonin.rovergnss.link.Sta): String {
    val v = s.batV ?: return "–"
    return if (v < 2.5 || v > 4.4) "chyba měření (${fmt(v, 2)} V)" else "${fmt(v, 2)} V (${s.batPct} %)"
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SectionCard(title, null, modifier, null, content)
}

/**
 * Sbalitelná sekce (Nastavení): nadpis se stavem [summary], klepnutím rozbalí / sbalí; stav rozbalení se pamatuje
 * v [prefs] pod [key]. S [prefs] = null je sekce vždy rozbalená a bez stavu v nadpisu.
 */
@Composable
fun FoldSection(
    title: String,
    summary: String?,
    prefs: cz.antonin.rovergnss.data.Prefs?,
    key: String,
    content: @Composable () -> Unit,
) {
    if (prefs == null) {
        Section(title, content = content)
        return
    }
    var open by androidx.compose.runtime.saveable.rememberSaveable(key) {
        androidx.compose.runtime.mutableStateOf(prefs.isExpanded(key))
    }
    SectionCard(title, summary, Modifier, open to {
        open = !open
        prefs.setExpanded(key, open)
    }, content)
}

@Composable
private fun SectionCard(
    title: String,
    summary: String?,
    modifier: Modifier,
    fold: Pair<Boolean, () -> Unit>?,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (fold == null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            } else {
                Row(
                    Modifier.fillMaxWidth().clickable { fold.second() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (!summary.isNullOrEmpty()) Text(
                            summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(if (fold.first) "▴" else "▾", style = MaterialTheme.typography.titleMedium)
                }
                if (fold.first) content()
            }
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = true, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = valueColor ?: Color.Unspecified,
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
