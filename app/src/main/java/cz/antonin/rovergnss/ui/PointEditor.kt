package cz.antonin.rovergnss.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.data.PointQuality
import cz.antonin.rovergnss.data.PointSource
import cz.antonin.rovergnss.geo.Angles
import cz.antonin.rovergnss.geo.Krovak
import cz.antonin.rovergnss.link.Pos
import java.util.Locale

private fun num(v: Double?, dec: Int) = if (v == null) "" else String.format(Locale.US, "%.${dec}f", v).replace('.', ',')

/**
 * Úprava bodu. Zadává se S-JTSK + Bpv nebo ETRS89 (úhly i ve tvaru 49°55′9,8368″); chybějící systém dopočte uložení.
 * @param rover aktuální poloha roveru (pro „Převzít polohu roveru“), null = bez polohy
 */
@Composable
fun PointEditor(
    initial: Point,
    rover: Pos?,
    antennaH: Double,
    onSave: (Point) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
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
    var err by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun build(): Point? {
        val py = Angles.parseNumber(y)
        val px = Angles.parseNumber(x)
        val pl = Angles.parseAngle(lat)
        val pn = Angles.parseAngle(lon)
        if ((y.isNotBlank() && py == null) || (x.isNotBlank() && px == null)) return null.also { err = "Y/X nejde přečíst" }
        if ((lat.isNotBlank() && pl == null) || (lon.isNotBlank() && pn == null)) return null.also { err = "φ/λ nejde přečíst" }
        if (bpv.isNotBlank() && Angles.parseNumber(bpv) == null) return null.also { err = "Bpv nejde přečíst" }
        if (hEll.isNotBlank() && Angles.parseNumber(hEll) == null) return null.also { err = "h nejde přečíst" }
        val hasJ = py != null && px != null
        val hasE = pl != null && pn != null
        if (!hasJ && !hasE) return null.also { err = "Zadej S-JTSK (Y, X) nebo ETRS89 (φ, λ)" }
        if (name.isBlank()) return null.also { err = "Chybí název" }
        return initial.copy(
            name = name.trim(), kind = kind.trim(), note = note.trim(), url = url.trim().ifEmpty { null },
            y = if (hasJ) kotlin.math.abs(py!!) else null, x = if (hasJ) kotlin.math.abs(px!!) else null, jtskDerived = false,
            lat = if (hasE) pl else null, lon = if (hasE) pn else null, etrsDerived = false,
            hEll = Angles.parseNumber(hEll), bpv = Angles.parseNumber(bpv),
            quality = quality, source = source,
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (initial.id == 0L) "Nový bod" else "Bod ${initial.name}", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(name, { name = it }, label = { Text("Název / číslo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(kind, { kind = it }, label = { Text("Druh") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Text("S-JTSK a výška Bpv", style = MaterialTheme.typography.titleSmall)
                Row {
                    NumField(y, { y = it }, "Y [m]", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    NumField(x, { x = it }, "X [m]", Modifier.weight(1f))
                }
                NumField(bpv, { bpv = it }, "Bpv [m]", Modifier.fillMaxWidth())

                Text("ETRS89", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(lat, { lat = it }, label = { Text("φ (49°55′9,8368″ nebo 49,919399)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lon, { lon = it }, label = { Text("λ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumField(hEll, { hEll = it }, "h elipsoidická [m]", Modifier.fillMaxWidth())
                Text(
                    "Stačí jeden systém, druhý se dopočte (7 parametrů, ~0,4 m). Prázdná pole = dopočtená.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        y = ""; x = ""; lat = ""; lon = ""; hEll = ""
                        rover?.etrs()?.let { g ->
                            lat = Angles.dms(g.lat, 5)
                            lon = Angles.dms(g.lon, 5)
                            hEll = num(g.h - antennaH, 3)
                            val j = Krovak.fromEtrs(g.lat, g.lon, g.h - antennaH)
                            y = num(j.y, 3)
                            x = num(j.x, 3)
                            source = PointSource.MEASURED
                            quality = PointQuality.APPROX
                        }
                    }, enabled = rover?.hasFix == true) { Text("Poloha roveru") }
                }
                rover?.let { if (it.hasFix) Text("Rover: ${it.quality.label}, hAcc ${fmt(it.hAcc, 2)} m – jedna epocha, ne měření bodu", style = MaterialTheme.typography.bodySmall) }

                Text("Kvalita polohy", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PointQuality.NAMES.forEachIndexed { i, n ->
                        FilterChip(selected = quality == i, onClick = { quality = i }, label = { Text(n) })
                    }
                }
                Text("Zdroj: ${PointSource.NAMES.getOrElse(source) { "?" }}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(note, { note = it }, label = { Text("Poznámka") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Geodetické údaje (odkaz)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { build()?.let(onSave) }) { Text("Uložit") }
                    OutlinedButton(onClick = onDismiss) { Text("Zrušit") }
                    if (onDelete != null) {
                        TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                            Text(if (confirmDelete) "Opravdu smazat" else "Smazat", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumField(v: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        v, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
