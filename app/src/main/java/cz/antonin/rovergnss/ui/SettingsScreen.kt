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
private const val ANT_MAX_CM = 300.0
private val STAKE_SEC = listOf(3 to "3 s", 5 to "5 s", 10 to "10 s", 20 to "20 s")
// měření bodu REC (0.10.0)
private val POINT_FIX = listOf(10 to "10 s", 20 to "20 s", 30 to "30 s", 60 to "1 min", 120 to "2 min")
private val POINT_SIGMA_H = listOf(0.01 to "1 cm", 0.02 to "2 cm", 0.03 to "3 cm", 0.05 to "5 cm")
private val POINT_SIGMA_U = listOf(0.02 to "2 cm", 0.03 to "3 cm", 0.05 to "5 cm", 0.10 to "10 cm")
private val POINT_JUMP_H = listOf(0.03 to "3 cm", 0.05 to "5 cm", 0.10 to "10 cm", 0.20 to "20 cm")
private val POINT_JUMP_V = listOf(0.05 to "5 cm", 0.10 to "10 cm", 0.20 to "20 cm", 0.30 to "30 cm")
private val POINT_TIMEOUT = listOf(60 to "1 min", 120 to "2 min", 180 to "3 min", 300 to "5 min", 600 to "10 min")
private val SIGNAL_MODES = listOf(0 to "vypnuto", 1 to "zvuk", 2 to "vibrace", 3 to "zvuk i vibrace")

/** Karta Nastavení: rover, korekce NTRIP, uživatelské profily, aplikace. */
@Composable
fun SettingsScreen(c: RoverController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RoverSection(c, fold = true)
        RoverSetupSection(c)
        NtripSection(c, fold = true)
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

    val linkSt by c.linkState.collectAsStateWithLifecycle()
    val missing = linkSt is cz.antonin.rovergnss.link.LinkState.Connected && roverProfiles.isNotEmpty() &&
        u.roverName.isNotEmpty() && u.roverName !in roverProfiles
    val summary = "${u.name}, anténa ${antCm(u.antennaH)}" +
        (if (u.roverName.isNotEmpty()) ", přijímač ${u.roverName}" + (if (missing) " (v roveru není)" else "") else "")
    FoldSection("Profil", summary, c.prefs, "profile") {
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
        Text(
            "Nový profil je kopie aktivního. Přepínání štítkem vlevo nahoře v Živě nebo tlačítkem na roveru " +
                "(profil přijímače se stejným názvem).",
            style = MaterialTheme.typography.bodySmall,
        )

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
    var rpMenu by remember { mutableStateOf(false) }
    val antCmVal = antH.replace(',', '.').toDoubleOrNull()
    val antErr = antCmVal?.takeIf { it in 0.0..ANT_MAX_CM } == null
    val everyErr = every.toIntOrNull()?.takeIf { it in 1..50 } == null

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

    Text("Nastavení přijímače", style = MaterialTheme.typography.titleSmall)
    Box {
        val label = when {
            u.roverName.isNotEmpty() -> u.roverName + if (roverProfiles.isNotEmpty() && u.roverName !in roverProfiles) " (v roveru není)" else ""
            u.roverProfile >= 0 -> roverProfiles.getOrNull(u.roverProfile) ?: "profil ${u.roverProfile + 1}"
            else -> "neměnit"
        }
        OutlinedButton(onClick = { rpMenu = true }) { Text("Při výběru přepnout: $label") }
        DropdownMenu(expanded = rpMenu, onDismissRequest = { rpMenu = false }) {
            DropdownMenuItem(text = { Text("neměnit") }, onClick = {
                rpMenu = false
                onChange(u.copy(roverProfile = -1, roverName = ""))
            })
            roverProfiles.forEach { n ->
                DropdownMenuItem(text = { Text(n) }, onClick = {
                    rpMenu = false
                    onChange(u.copy(roverProfile = -1, roverName = n))
                })
            }
        }
    }
    if (roverProfiles.isEmpty()) Text("Seznam profilů přijímače se načte po připojení roveru.", style = MaterialTheme.typography.bodySmall)

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

    Text("Měření bodu (REC)", style = MaterialTheme.typography.titleSmall)
    Text(
        "Do průměru jen RTK fix. Konec sám, když je splněno vše: doba fixu, σ průměru, bez skoku za posledních 30 s " +
            "(a ověření fixu, je-li zapnuto). Po limitu bez splnění „nejde změřit“ – rozhoduješ ty.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Aspoň RTK fix", style = MaterialTheme.typography.bodyMedium)
    Chips(POINT_FIX, u.pointMinFixSec) { onChange(u.copy(pointMinFixSec = it)) }
    Text("σ průměru vodorovně do", style = MaterialTheme.typography.bodyMedium)
    Chips(POINT_SIGMA_H, u.pointSigmaH) { onChange(u.copy(pointSigmaH = it)) }
    Text("σ průměru výšky do", style = MaterialTheme.typography.bodyMedium)
    Chips(POINT_SIGMA_U, u.pointSigmaU) { onChange(u.copy(pointSigmaU = it)) }
    Text("Skok během fixu – vodorovně / výška", style = MaterialTheme.typography.bodyMedium)
    Chips(POINT_JUMP_H, u.pointJumpH) { onChange(u.copy(pointJumpH = it)) }
    Chips(POINT_JUMP_V, u.pointJumpV) { onChange(u.copy(pointJumpV = it)) }
    Text("„Nejde změřit“ po", style = MaterialTheme.typography.bodyMedium)
    Chips(POINT_TIMEOUT, u.pointTimeoutSec) { onChange(u.copy(pointTimeoutSec = it)) }
    CheckRow("ověření fixu (horký start X20 → nové určení fixu, porovnání)", u.pointVerify) { onChange(u.copy(pointVerify = it)) }

    Text("Vytyčení – průměrování polohy", style = MaterialTheme.typography.titleSmall)
    Chips(STAKE_SEC, u.stakeSec) { onChange(u.copy(stakeSec = it)) }

    Text("Měření kontrolního bodu", style = MaterialTheme.typography.titleSmall)
    Chips(MEASURE_SEC, u.measureSec) { onChange(u.copy(measureSec = it)) }
    CheckRow("X20 během měření ve statickém modelu", u.measStatic) { onChange(u.copy(measStatic = it)) }
    CheckRow("ukládat surová data RAWX/SFRBX (.ubx, ~1,5 kB/s)", u.measRaw) { onChange(u.copy(measRaw = it)) }
    Text(
        "Statický model a RAWX vyžadují firmware 0.9.0+. Statický model platí i pro měření bodu (REC). " +
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
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    var signal by remember { mutableStateOf(c.prefs.signalMode) }
    FoldSection("Aplikace", "verze $version", c.prefs, "app") {
        KeyValue("Verze", version)
        Text("Signalizace měření bodu", style = MaterialTheme.typography.titleSmall)
        Chips(SIGNAL_MODES, signal) {
            signal = it
            c.prefs.signalMode = it
        }
        Text(
            "Zvuk kanálem alarmu (hraje i při ztlumených médiích), i se zhasnutým displejem. " +
                "Fix naskočil · fix ztracen · bod změřen · podezřelý fix · nejde změřit:",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            cz.antonin.rovergnss.service.Signals.Kind.entries.forEach { k ->
                OutlinedButton(onClick = { c.testSignal(k) }) {
                    Text(
                        when (k) {
                            cz.antonin.rovergnss.service.Signals.Kind.FIX -> "fix"
                            cz.antonin.rovergnss.service.Signals.Kind.LOST -> "ztráta"
                            cz.antonin.rovergnss.service.Signals.Kind.DONE -> "změřen"
                            cz.antonin.rovergnss.service.Signals.Kind.SUSPECT -> "podezřelý"
                            cz.antonin.rovergnss.service.Signals.Kind.FAILED -> "nejde"
                        }
                    )
                }
            }
        }
        ExitButton(c)
    }
}
