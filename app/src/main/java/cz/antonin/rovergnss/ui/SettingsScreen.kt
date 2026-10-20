package cz.antonin.rovergnss.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.UserProfile
import cz.antonin.rovergnss.service.RoverController

private val MEASURE_SEC = listOf(60 to "1 min", 180 to "3 min", 600 to "10 min", 1800 to "30 min", 0 to "ručně")
private val SURVEY_MAX = listOf(10 to "10 s", 30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min")
private val SURVEY_SIGMA = listOf(0.01 to "1 cm", 0.02 to "2 cm", 0.05 to "5 cm", 0.10 to "10 cm", 0.50 to "50 cm")
private const val ANT_MAX_CM = 300.0
private val STAKE_SEC = listOf(3 to "3 s", 5 to "5 s", 10 to "10 s", 20 to "20 s")

/** Karta Nastavení: rover, korekce NTRIP, uživatelské profily, aplikace. */
@Composable
fun SettingsScreen(c: RoverController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RoverSection(c)
        RoverSetupSection(c)
        NtripSection(c)
        UserProfileSection(c)
        AppSection(c)
        Spacer(Modifier.height(12.dp))
    }
}

/** Uživatelské profily: výběr, nový, přejmenování, smazání a hodnoty aktivního profilu. */
@Composable
private fun UserProfileSection(c: RoverController) {
    val users by c.users.collectAsStateWithLifecycle()
    val u by c.user.collectAsStateWithLifecycle()
    val roverProfiles by c.roverProfiles.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }   // new / rename / delete
    var text by remember { mutableStateOf("") }
    val idx = c.prefs.userIndex.coerceIn(0, (users.size - 1).coerceAtLeast(0))

    Section("Uživatelský profil") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { menu = true }) { Text("Profil: ${u.name} ▾", style = MaterialTheme.typography.titleSmall) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    users.forEachIndexed { i, p ->
                        DropdownMenuItem(text = { Text(p.name + if (i == idx) "  ✓" else "") }, onClick = {
                            menu = false
                            c.selectUser(i)
                        })
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                text = ""
                dialog = "new"
            }) { Text("Nový") }
            OutlinedButton(onClick = {
                text = u.name
                dialog = "rename"
            }) { Text("Přejmenovat") }
            OutlinedButton(onClick = { dialog = "delete" }, enabled = users.size > 1) { Text("Smazat") }
        }
        Text("Nový profil je kopie aktivního. Přepínat lze i štítkem na mapě v Živě.", style = MaterialTheme.typography.bodySmall)

        // key = profil: při přepnutí se pole naplní znovu
        androidx.compose.runtime.key(idx, u.name) {
            UserProfileEditor(u, roverProfiles) { c.updateUser(it) }
        }
    }

    when (dialog) {
        "new", "rename" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (dialog == "new") "Nový profil" else "Přejmenovat profil") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Název, např. Výtyčka 2,000 m") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (dialog == "new") c.addUser(text) else c.updateUser(u.copy(name = text.trim()))
                    dialog = null
                }, enabled = text.isNotBlank()) { Text("Uložit") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Zrušit") } },
        )
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Smazat profil?") },
            text = { Text(u.name) },
            confirmButton = {
                TextButton(onClick = {
                    c.deleteUser(idx)
                    dialog = null
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Zrušit") } },
        )
    }
}

@Composable
private fun UserProfileEditor(u: UserProfile, roverProfiles: List<String>, onChange: (UserProfile) -> Unit) {
    // výška antény se zadává v cm, ukládá v m
    var antH by remember { mutableStateOf(String.format(java.util.Locale.US, "%.1f", u.antennaH * 100).removeSuffix(".0").replace('.', ',')) }
    var every by remember { mutableStateOf(u.recordEvery.toString()) }
    var minEp by remember { mutableStateOf(u.surveyMinEpochs.toString()) }
    var rpMenu by remember { mutableStateOf(false) }
    val antCmVal = antH.replace(',', '.').toDoubleOrNull()
    val antErr = antCmVal?.takeIf { it in 0.0..ANT_MAX_CM } == null
    val everyErr = every.toIntOrNull()?.takeIf { it in 1..50 } == null
    val minErr = minEp.toIntOrNull()?.takeIf { it in 1..1000 } == null

    Text("Anténa", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        antH, {
            antH = it
            it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v in 0.0..ANT_MAX_CM }?.let { v -> onChange(u.copy(antennaH = v / 100)) }
        },
        label = { Text("Výška antény nad značkou [cm]") }, singleLine = true, isError = antErr,
        supportingText = {
            when {
                antErr -> Text("Rozsah 0–${ANT_MAX_CM.toInt()} cm")
                antCmVal != null && antCmVal > 0 && antCmVal < 1 -> Text("Pod 1 cm – nezadal jsi výšku v metrech?")
                else -> {}
            }
        },
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )

    Text("Profil roveru", style = MaterialTheme.typography.titleSmall)
    Box {
        val label = if (u.roverProfile < 0) "neměnit"
        else roverProfiles.getOrNull(u.roverProfile)?.let { "${u.roverProfile + 1}: $it" } ?: "profil ${u.roverProfile + 1}"
        OutlinedButton(onClick = { rpMenu = true }) { Text("Při výběru přepnout: $label") }
        DropdownMenu(expanded = rpMenu, onDismissRequest = { rpMenu = false }) {
            DropdownMenuItem(text = { Text("neměnit") }, onClick = {
                rpMenu = false
                onChange(u.copy(roverProfile = -1))
            })
            roverProfiles.forEachIndexed { i, n ->
                DropdownMenuItem(text = { Text("${i + 1}: $n") }, onClick = {
                    rpMenu = false
                    onChange(u.copy(roverProfile = i))
                })
            }
        }
    }
    if (roverProfiles.isEmpty()) Text("Seznam profilů roveru se načte po připojení roveru.", style = MaterialTheme.typography.bodySmall)

    Text("Záznam trasy", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        every, {
            every = it
            it.toIntOrNull()?.takeIf { v -> v in 1..50 }?.let { v -> onChange(u.copy(recordEvery = v)) }
        },
        label = { Text("Ukládat každou n-tou epochu (1–50)") }, singleLine = true, isError = everyErr,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    CheckRow("ukládat i surový proud z roveru (~10 MB/h)", u.recordRaw) { onChange(u.copy(recordRaw = it)) }

    Text("Zaměření bodu (tlačítko na mapě)", style = MaterialTheme.typography.titleSmall)
    Text("Konec, co nastane dřív: ustálení σ, nebo nejdelší doba. Kdykoli lze uložit ručně.", style = MaterialTheme.typography.bodySmall)
    Text("Ustálení – σ vodorovně do", style = MaterialTheme.typography.bodyMedium)
    Chips(SURVEY_SIGMA, u.surveySigma) { onChange(u.copy(surveySigma = it)) }
    Text("Nejdéle", style = MaterialTheme.typography.bodyMedium)
    Chips(SURVEY_MAX, u.surveyMaxSec) { onChange(u.copy(surveyMaxSec = it)) }
    OutlinedTextField(
        minEp, {
            minEp = it
            it.toIntOrNull()?.takeIf { v -> v in 1..1000 }?.let { v -> onChange(u.copy(surveyMinEpochs = v)) }
        },
        label = { Text("Aspoň epoch pro ustálení") }, singleLine = true, isError = minErr,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

    Text("Vytyčení – průměrování polohy", style = MaterialTheme.typography.titleSmall)
    Chips(STAKE_SEC, u.stakeSec) { onChange(u.copy(stakeSec = it)) }

    Text("Měření kontrolního bodu", style = MaterialTheme.typography.titleSmall)
    Chips(MEASURE_SEC, u.measureSec) { onChange(u.copy(measureSec = it)) }
    CheckRow("X20 během měření ve statickém modelu", u.measStatic) { onChange(u.copy(measStatic = it)) }
    CheckRow("ukládat surová data RAWX/SFRBX (.ubx, ~1,5 kB/s)", u.measRaw) { onChange(u.copy(measRaw = it)) }
    Text(
        "Statický model a RAWX vyžadují firmware 0.9.0+. Statický model platí i pro zaměření bodu. " +
            "Soubor .ubx je pro dodatečné zpracování (PPK, např. RTKLIB) – export u měření bodu.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun <T> Chips(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (v, label) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) }) }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

@Composable
private fun AppSection(c: RoverController) {
    val context = LocalContext.current
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
}
