package cz.antonin.rovergnss.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.data.PointQuality
import cz.antonin.rovergnss.data.PointSource
import cz.antonin.rovergnss.geo.Angles
import java.util.Locale

private fun num(v: Double?, dec: Int) = if (v == null) "" else String.format(Locale.US, "%.${dec}f", v).replace('.', ',')

/**
 * Úprava bodu. Zadává se S-JTSK + Bpv nebo ETRS89 (úhly i ve tvaru 49°55′9,8368″); chybějící systém dopočte uložení.
 * Souřadnice bodu jsou dané (ruční zadání, import, ČÚZK) – měření roveru je nikdy nepřepisuje, ukládá se zvlášť.
 * Kreslí se jako vrstva přes obsah aplikace (ne samostatné okno), aby respektovala lišty Androidu.
 * @param extra další obsah pod formulářem (navigace, měření bodu) – jen u uloženého bodu
 */
@Composable
fun PointEditor(
    initial: Point,
    onSave: (Point) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var note by remember { mutableStateOf(initial.note) }
    // dopočtené hodnoty se do polí nepředvyplňují – při uložení se dopočtou znovu (po změně druhého systému)
    var y by remember { mutableStateOf(if (initial.jtskDerived) "" else num(initial.y, 3)) }
    var x by remember { mutableStateOf(if (initial.jtskDerived) "" else num(initial.x, 3)) }
    var bpv by remember { mutableStateOf(num(initial.bpv, 3)) }
    var lat by remember { mutableStateOf(if (initial.etrsDerived) "" else initial.lat?.let { Angles.dms(it, 5) } ?: "") }
    var lon by remember { mutableStateOf(if (initial.etrsDerived) "" else initial.lon?.let { Angles.dms(it, 5) } ?: "") }
    var hEll by remember { mutableStateOf(if (initial.etrsDerived) "" else num(initial.hEll, 3)) }
    var quality by remember { mutableIntStateOf(initial.quality) }
    var source by remember { mutableIntStateOf(initial.source) }
    var url by remember { mutableStateOf(initial.url ?: "") }
    var control by remember { mutableStateOf(initial.control) }
    var hOff by remember { mutableStateOf(if (initial.hOffset == 0.0) "" else num(initial.hOffset, 3)) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // změřený bod (REC, 0.13.0): souřadnice jen ke čtení – mění je jen nové měření
    val measured = initial.id != 0L && initial.source == PointSource.MEASURED

    fun buildMeasured(): Point? {
        if (name.isBlank()) return null.also { err = "Chybí název" }
        val off = if (hOff.isBlank()) 0.0 else Angles.parseNumber(hOff) ?: return null.also { err = "Excentr nejde přečíst" }
        if (kotlin.math.abs(off) > 100) return null.also { err = "Excentr nad 100 m" }
        val dOff = off - initial.hOffset
        return initial.copy(
            name = name.trim(), kind = kind.trim(), note = note.trim(), url = url.trim().ifEmpty { null }, control = control,
            hOffset = off, hEll = initial.hEll?.plus(dOff), bpv = initial.bpv?.plus(dOff),
        )
    }

    fun build(): Point? {
        if (measured) return buildMeasured()
        val py = Angles.parseNumber(y)
        val px = Angles.parseNumber(x)
        val pl = Angles.parseAngle(lat)
        val pn = Angles.parseAngle(lon)
        if ((y.isNotBlank() && py == null) || (x.isNotBlank() && px == null)) return null.also { err = "Y/X nejde přečíst" }
        if ((lat.isNotBlank() && pl == null) || (lon.isNotBlank() && pn == null)) return null.also { err = "φ/λ nejde přečíst" }
        if (bpv.isNotBlank() && Angles.parseNumber(bpv) == null) return null.also { err = "Bpv nejde přečíst" }
        if (hEll.isNotBlank() && Angles.parseNumber(hEll) == null) return null.also { err = "h nejde přečíst" }
        val off = if (hOff.isBlank()) 0.0 else Angles.parseNumber(hOff) ?: return null.also { err = "Excentr nejde přečíst" }
        if (kotlin.math.abs(off) > 100) return null.also { err = "Excentr nad 100 m" }
        // změna excentru posune výšku bodu (h i Bpv o stejnou hodnotu – kvazigeoid se na místě nemění)
        val dOff = off - initial.hOffset
        val hasJ = py != null && px != null
        val hasE = pl != null && pn != null
        if (!hasJ && !hasE) return null.also { err = "Zadej S-JTSK (Y, X) nebo ETRS89 (φ, λ)" }
        if (name.isBlank()) return null.also { err = "Chybí název" }
        return initial.copy(
            name = name.trim(), kind = kind.trim(), note = note.trim(), url = url.trim().ifEmpty { null },
            y = if (hasJ) kotlin.math.abs(py!!) else null, x = if (hasJ) kotlin.math.abs(px!!) else null, jtskDerived = false,
            lat = if (hasE) pl else null, lon = if (hasE) pn else null, etrsDerived = false,
            hEll = Angles.parseNumber(hEll)?.plus(dOff), bpv = Angles.parseNumber(bpv)?.plus(dOff),
            quality = quality, source = source, control = control, hOffset = off,
        )
    }

    BackHandler(enabled = !confirmDelete, onBack = onDismiss)
    // vrstva v obsahu aplikace: okraje lišt už řeší Scaffold, klávesnice imePadding
    Surface(Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (initial.id == 0L) "Nový bod" else "Bod ${initial.name}",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onDismiss) { Text("Zrušit") }
                Button(onClick = { build()?.let(onSave) }) { Text("Uložit") }
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            HorizontalDivider()
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Název / číslo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(kind, { kind = it }, label = { Text("Druh") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                if (measured) MeasuredCoords(initial) else Text("S-JTSK a výška Bpv", style = MaterialTheme.typography.titleSmall)
                if (!measured) Row {
                    NumField(y, { y = it }, "Y [m]", Modifier.weight(1f), if (initial.jtskDerived) num(initial.y, 2) else "")
                    Spacer(Modifier.width(8.dp))
                    NumField(x, { x = it }, "X [m]", Modifier.weight(1f), if (initial.jtskDerived) num(initial.x, 2) else "")
                }
                if (!measured) {
                if (initial.jtskDerived && y.isEmpty()) Text(
                    "dopočteno z ETRS89: Y ${num(initial.y, 3)} · X ${num(initial.x, 3)} (při uložení se přepočte)",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumField(bpv, { bpv = it }, "Bpv [m]", Modifier.fillMaxWidth())

                Text("ETRS89", style = MaterialTheme.typography.titleSmall)
                val dLat = if (initial.etrsDerived) initial.lat?.let { Angles.dms(it, 4) } ?: "" else ""
                val dLon = if (initial.etrsDerived) initial.lon?.let { Angles.dms(it, 4) } ?: "" else ""
                OutlinedTextField(lat, { lat = it }, label = { Text("φ (49°55′9,8368″ nebo 49,919399)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), placeholder = { if (dLat.isNotEmpty()) Text(dLat) })
                OutlinedTextField(lon, { lon = it }, label = { Text("λ") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), placeholder = { if (dLon.isNotEmpty()) Text(dLon) })
                NumField(hEll, { hEll = it }, "h elipsoidická [m]", Modifier.fillMaxWidth(), if (initial.etrsDerived) num(initial.hEll, 2) else "")
                if (initial.etrsDerived && lat.isEmpty()) Text(
                    "dopočteno ze S-JTSK: φ ${initial.lat?.let { Angles.dms(it, 5) } ?: "–"} · λ ${initial.lon?.let { Angles.dms(it, 5) } ?: "–"}" +
                        " · h ${num(initial.hEll, 3)} m (při uložení se přepočte)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Stačí jeden systém, druhý se dopočte převodem ČÚZK (~1–2 cm, h elipsoidu z Bpv přes kvazigeoid). Prázdná pole = dopočtená.",
                    style = MaterialTheme.typography.bodySmall,
                )
                }

                Text("Výškový excentr", style = MaterialTheme.typography.titleSmall)
                NumField(hOff, { hOff = it }, "Excentr výšky [m] (např. −1,80)", Modifier.fillMaxWidth())
                Text(
                    "Cíl = změřené místo + excentr. Pro místa bez fixu (hladina, potok pod stromy): změř nejbližší volné místo " +
                        "s fixem a výšku k cíli změř pásmem nebo latí. Uložení posune h i Bpv bodu" +
                        (if (initial.hOffset != 0.0) " (teď je v nich excentr ${num(initial.hOffset, 3)} m)." else "."),
                    style = MaterialTheme.typography.bodySmall,
                )

                if (!measured) Text("Kvalita polohy", style = MaterialTheme.typography.titleSmall)
                if (!measured) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PointQuality.NAMES.forEachIndexed { i, n ->
                        FilterChip(selected = quality == i, onClick = { quality = i }, label = { Text(n) })
                    }
                }
                Text("Zdroj: ${PointSource.NAMES.getOrElse(source) { "?" }}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(control, { control = it })
                    Text("Kontrolní bod (opakovaná měření přijímače)")
                }
                OutlinedTextField(note, { note = it }, label = { Text("Poznámka") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Geodetické údaje (odkaz)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                extra()

                if (onDelete != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmDelete = true }) {
                        Text("Smazat bod", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
          }
        if (confirmDelete && onDelete != null) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Smazat bod?") },
                text = { Text("${initial.name} i s měřeními – nelze vrátit.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        onDelete()
                    }) { Text("Smazat", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Zrušit") } },
            )
        }
    }
}

/** Souřadnice změřeného bodu jen ke čtení (0.13.0): S-JTSK, Bpv, ETRS89, excentr, kvalita. */
@Composable
private fun MeasuredCoords(p: Point) {
    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    Text("Souřadnice – změřeno (mění je jen nové měření)", style = MaterialTheme.typography.titleSmall)
    @Composable
    fun kv(k: String, v: String) {
        Row(Modifier.fillMaxWidth()) {
            Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(v, fontFamily = mono, style = MaterialTheme.typography.bodyMedium)
        }
    }
    kv("S-JTSK Y / X", "${num(p.y, 3)} / ${num(p.x, 3)}")
    kv("H Bpv", num(p.bpv, 3) + " m")
    kv("φ ETRS89", p.lat?.let { Angles.dms(it, 5) } ?: "–")
    kv("λ ETRS89", p.lon?.let { Angles.dms(it, 5) } ?: "–")
    kv("h elipsoid", num(p.hEll, 3) + " m")
    if (p.hOffset != 0.0) kv("v tom excentr výšky", num(p.hOffset, 3) + " m")
    kv("kvalita", PointQuality.NAMES.getOrElse(p.quality) { "?" })
    Text(
        "Poloha antény na značce (výška antény odečtena), S-JTSK a Bpv převodem ČÚZK.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun NumField(v: String, onChange: (String) -> Unit, label: String, modifier: Modifier, hint: String = "") {
    OutlinedTextField(
        v, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        placeholder = { if (hint.isNotEmpty()) Text(hint) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
