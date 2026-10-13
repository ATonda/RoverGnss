package cz.antonin.rovergnss.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.antonin.rovergnss.geo.Sjtsk
import cz.antonin.rovergnss.service.RoverController

private val MEASURE_SEC = listOf(60 to "1 min", 180 to "3 min", 600 to "10 min", 1800 to "30 min", 0 to "ručně")
private val ROTATE_NAMES = listOf("sever nahoře", "směr pohybu", "volně")

/** Karta Nastavení: anténa, záznam, měření bodu, mapa, souřadnice, aplikace. */
@Composable
fun SettingsScreen(c: RoverController) {
    val context = LocalContext.current
    var antH by remember { mutableStateOf(fmtInput(c.prefs.antennaH)) }
    var every by remember { mutableStateOf(c.prefs.recordEvery.toString()) }
    var raw by remember { mutableStateOf(c.prefs.recordRaw) }
    var measSec by remember { mutableIntStateOf(c.prefs.measureSec) }
    var measStatic by remember { mutableStateOf(c.prefs.measStatic) }
    var measRaw by remember { mutableStateOf(c.prefs.measRaw) }
    var style by remember { mutableIntStateOf(c.prefs.mapStyle) }
    var rotate by remember { mutableIntStateOf(c.prefs.mapRotate) }
    val antErr = antH.isNotBlank() && antH.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..10.0 } == null
    val everyErr = every.toIntOrNull()?.takeIf { it in 1..50 } == null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section("Anténa") {
            OutlinedTextField(
                antH, {
                    antH = it
                    it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v in 0.0..10.0 }?.let { v -> c.prefs.antennaH = v }
                },
                label = { Text("Výška antény nad značkou [m]") }, singleLine = true, isError = antErr,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Text(
                "Odečítá se v živém panelu, navigaci, při měření bodu a v exportu tras (ukládá se k nové trase a měření).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Section("Záznam trasy") {
            OutlinedTextField(
                every, {
                    every = it
                    it.toIntOrNull()?.takeIf { v -> v in 1..50 }?.let { v -> c.prefs.recordEvery = v }
                },
                label = { Text("Ukládat každou n-tou epochu (1–50)") }, singleLine = true, isError = everyErr,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(raw, {
                    raw = it
                    c.prefs.recordRaw = it
                })
                Text("ukládat i surový proud z roveru (~10 MB/h)")
            }
            Text("Měření bodu ukládá vždy každou epochu.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Měření bodu") {
            Text("Výchozí délka měření", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MEASURE_SEC.forEach { (s, label) ->
                    FilterChip(selected = measSec == s, onClick = {
                        measSec = s
                        c.prefs.measureSec = s
                    }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(measStatic, {
                    measStatic = it
                    c.prefs.measStatic = it
                })
                Text("X20 během měření ve statickém modelu")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(measRaw, {
                    measRaw = it
                    c.prefs.measRaw = it
                })
                Text("ukládat surová data RAWX/SFRBX (.ubx, ~1,5 kB/s)")
            }
            Text(
                "Vyžaduje firmware 0.9.0+. Po měření rover vrátí model z profilu. Soubor .ubx je pro dodatečné " +
                    "zpracování (PPK, např. RTKLIB) – export u měření bodu.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Section("Mapa") {
            Text("Podklad", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapStyles.NAMES.forEachIndexed { i, n ->
                    FilterChip(selected = style == i, onClick = {
                        style = i
                        c.prefs.mapStyle = i
                    }, label = { Text(n) })
                }
            }
            Text("Natočení", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ROTATE_NAMES.forEachIndexed { i, n ->
                    FilterChip(selected = rotate == i, onClick = {
                        rotate = i
                        c.prefs.mapRotate = i
                    }, label = { Text(n) })
                }
            }
        }
        Section("Souřadnice") {
            KeyValue("Převod S-JTSK", if (Sjtsk.ready) "ČÚZK, tabulka v1710" else "7 parametrů (tabulky nenačtené)", mono = false)
            KeyValue("Výška Bpv", if (Sjtsk.ready) "kvazigeoid CR-2005" else "–", mono = false)
            Text(
                "Postup podle metodiky ČÚZK (ETRF2000 ↔ S-JTSK/05 ↔ S-JTSK), přesnost ~1–2 cm. Mimo ČR 7 parametrů (~0,4 m).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Section("Aplikace") {
            val version = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (e: Exception) {
                    "?"
                }
            }
            KeyValue("Verze", version)
            ExitButton(c)
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun fmtInput(v: Double): String = if (v == 0.0) "0" else v.toString().replace('.', ',')
