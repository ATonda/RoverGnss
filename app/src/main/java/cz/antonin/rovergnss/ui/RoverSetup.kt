package cz.antonin.rovergnss.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import cz.antonin.rovergnss.link.LinkState
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Profil roveru v editoru; [raw] drží položky, které editor nezná (zůstanou beze změny). */
private data class RoverProf(
    val name: String,
    val rate: Int,
    val dyn: String,
    val elev: Int,
    val sbas: Boolean,
    val has: Boolean,
    val buzzer: Boolean,
    val gga: Boolean,
    val rmc: Boolean,
    val gst: Boolean,
    val gsa: Boolean,
    val gsv: Boolean,
    val raw: String,
) {
    fun toJson(): JSONObject = JSONObject(raw).apply {
        put("name", name)
        put("rate", rate)
        put("dyn", dyn)
        put("elev", elev)
        put("sbas", sbas)
        put("has", has)
        put("buzzer", buzzer)
        put("nmea", JSONObject().put("gga", gga).put("rmc", rmc).put("gst", gst).put("gsa", gsa).put("gsv", gsv))
    }

    companion object {
        fun of(o: JSONObject): RoverProf {
            val n = o.optJSONObject("nmea")
            return RoverProf(
                name = o.optString("name", "Profil"), rate = o.optInt("rate", 5), dyn = o.optString("dyn", "pedestrian"),
                elev = o.optInt("elev", 10), sbas = o.optBoolean("sbas", true), has = o.optBoolean("has", true),
                buzzer = o.optBoolean("buzzer", true),
                gga = n?.optBoolean("gga", true) ?: true, rmc = n?.optBoolean("rmc", true) ?: true,
                gst = n?.optBoolean("gst", true) ?: true, gsa = n?.optBoolean("gsa", true) ?: true,
                gsv = n?.optBoolean("gsv", true) ?: true, raw = o.toString(),
            )
        }
    }
}

private val DYN = listOf("auto" to "automaticky", "pedestrian" to "chodec", "automotive" to "auto", "stationary" to "statika")
private val RATES = listOf(1, 5, 10)

/** Nastavení roveru z aplikace (FW 0.13.0+): profily, název BT, kalibrace, průchozí režim, restart. */
@Composable
internal fun RoverSetupSection(c: RoverController) {
    val link by c.linkState.collectAsStateWithLifecycle()
    val info by c.info.collectAsStateWithLifecycle()
    val sta by c.sta.collectAsStateWithLifecycle()
    if (link !is LinkState.Connected) return
    val i = info ?: return
    Section("Nastavení roveru") {
        if (!i.canSetup) {
            Text("Rover má firmware ${i.fw} – nastavení z aplikace vyžaduje FW 0.13.0+.", style = MaterialTheme.typography.bodySmall)
            return@Section
        }
        sta?.let { s ->
            KeyValue("Zdroj korekcí (X20)", (s.corrSourceText ?: "–") + (s.corrSignals?.let { ", $it sig." } ?: ""))
            s.motionText?.let { m ->
                KeyValue("Stav / model X20", "$m / ${s.dynText ?: "–"}" + if (s.motionAuto) " (automaticky)" else "")
                KeyValue(
                    "|a| σ, ω max, kroky",
                    "${s.accStdMg ?: "–"} mg, ${fmt(s.gyroMax, 0)} °/s, ${s.stepScore ?: "–"} %" + (s.stepHz?.takeIf { it > 0 }?.let { " (${fmt(it, 1)} Hz)" } ?: ""),
                )
            }
            KeyValue("Nastavení SBAS/HAS", when (s.corrCfg) {
                1 -> "OK"
                0 -> "X20 odmítla (NAK)"
                -1 -> "bez odpovědi"
                else -> "–"
            })
        }
        ProfilesEditor(c)
        HorizontalDivider()
        DeviceEditor(c)
        HorizontalDivider()
        CalEditor(c)
        HorizontalDivider()
        Text("Servis", style = MaterialTheme.typography.titleSmall)
        val pass = sta?.passthrough == true
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!pass) OutlinedButton(onClick = { c.roverPassthrough(true) }) { Text("Průchozí režim USB ↔ X20") }
            else Button(onClick = { c.roverPassthrough(false) }) { Text("Ukončit průchozí režim") }
            var ask by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { ask = true }) { Text("Restart roveru") }
            if (ask) AlertDialog(
                onDismissRequest = { ask = false },
                title = { Text("Restartovat rover?") },
                text = { Text("Restart ESP (stejně jako podržení tlačítka 5 s). X20 běží dál a po startu dostane znovu konfiguraci.") },
                confirmButton = {
                    TextButton(onClick = {
                        ask = false
                        c.restartRover()
                    }) { Text("Restartovat") }
                },
                dismissButton = { TextButton(onClick = { ask = false }) { Text("Zrušit") } },
            )
        }
        Text(
            "Průchozí režim: u-center na PC přes USB kabel roveru (rychlost UART X20, obvykle 460800 Bd). " +
                "Poloha mezitím nechodí. Konec zde, krátkým stiskem tlačítka nebo vypnutím.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ProfilesEditor(c: RoverController) {
    val scope = rememberCoroutineScope()
    var profs by remember { mutableStateOf<List<RoverProf>>(emptyList()) }
    var def by remember { mutableIntStateOf(0) }
    var active by remember { mutableIntStateOf(0) }
    var max by remember { mutableIntStateOf(10) }
    var open by remember { mutableIntStateOf(-1) }
    var msg by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }

    suspend fun load() {
        val d = c.roverProfilesJson() ?: run {
            msg = "Profily se nepodařilo načíst"
            return
        }
        val a = d.optJSONArray("profiles") ?: JSONArray()
        profs = (0 until a.length()).map { RoverProf.of(a.getJSONObject(it)) }
        def = d.optInt("default")
        active = d.optInt("active")
        max = d.optInt("max", 10)
        dirty = false
        msg = null
    }
    LaunchedEffect(Unit) { load() }

    fun upd(i: Int, f: (RoverProf) -> RoverProf) {
        profs = profs.mapIndexed { k, p -> if (k == i) f(p) else p }
        dirty = true
    }

    Text("Profily roveru (přepínání krátkým stiskem tlačítka)", style = MaterialTheme.typography.titleSmall)
    profs.forEachIndexed { k, p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = def == k, onClick = {
                def = k
                dirty = true
            })
            Column(Modifier.weight(1f)) {
                Text("${k + 1}. ${p.name}" + if (k == active) " (aktivní)" else "")
                Text(
                    "${p.rate} Hz, ${DYN.firstOrNull { it.first == p.dyn }?.second ?: p.dyn}, maska ${p.elev}°, " +
                        "SBAS ${if (p.sbas) "zap" else "vyp"}, HAS ${if (p.has) "zap" else "vyp"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { open = if (open == k) -1 else k }) { Text(if (open == k) "Skrýt" else "Upravit") }
        }
        if (open == k) ProfileFields(p) { np -> upd(k) { np } }
    }
    Text(
        "○ = výchozí profil po zapnutí. Model „automaticky“ (FW 0.14.0+) přepíná podle stavu: Měřím = stationary, " +
            "Manipulace a Chodím = pedestrian, Jedu = automotive.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            scope.launch {
                val data = JSONObject().put("default", def).put("active", active.coerceIn(0, (profs.size - 1).coerceAtLeast(0)))
                    .put("profiles", JSONArray().apply { profs.forEach { put(it.toJson()) } })
                val err = c.saveRoverProfiles(data)
                msg = err?.let { "Chyba: $it" } ?: "Uloženo do roveru"
                if (err == null) load()
            }
        }, enabled = dirty && profs.isNotEmpty()) { Text("Uložit do roveru") }
        OutlinedButton(onClick = {
            val src = profs.getOrNull(open.coerceAtLeast(0)) ?: profs.lastOrNull() ?: return@OutlinedButton
            var n = 2
            while (profs.any { it.name == "${src.name.take(20)} $n" }) n++
            profs = profs + src.copy(name = "${src.name.take(20)} $n")
            open = profs.size - 1
            dirty = true
        }, enabled = profs.size < max) { Text("Kopie") }
        OutlinedButton(onClick = {
            val k = open
            if (k !in profs.indices || profs.size <= 1) return@OutlinedButton
            profs = profs.filterIndexed { i, _ -> i != k }
            if (def >= profs.size || def == k) def = 0 else if (def > k) def--
            if (active >= profs.size || active == k) active = def else if (active > k) active--
            open = -1
            dirty = true
        }, enabled = open in profs.indices && profs.size > 1) { Text("Smazat") }
        TextButton(onClick = { scope.launch { load() } }) { Text("Načíst znovu") }
    }
    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ProfileFields(p: RoverProf, onChange: (RoverProf) -> Unit) {
    var elev by remember(p.name) { mutableStateOf(p.elev.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(p.name, { if (it.length <= 24) onChange(p.copy(name = it)) }, label = { Text("Název (1–24 znaků)") },
            singleLine = true, isError = p.name.isBlank(), modifier = Modifier.fillMaxWidth())
        Text("Frekvence", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RATES.forEach { r -> FilterChip(selected = p.rate == r, onClick = { onChange(p.copy(rate = r)) }, label = { Text("$r Hz") }) }
        }
        Text("Dynamický model X20", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DYN.forEach { (k, l) -> FilterChip(selected = p.dyn == k, onClick = { onChange(p.copy(dyn = k)) }, label = { Text(l) }) }
        }
        OutlinedTextField(elev, {
            elev = it
            it.toIntOrNull()?.takeIf { v -> v in 0..60 }?.let { v -> onChange(p.copy(elev = v)) }
        }, label = { Text("Elevační maska [°] 0–60") }, singleLine = true, isError = elev.toIntOrNull()?.takeIf { it in 0..60 } == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Check("korekce SBAS", p.sbas) { onChange(p.copy(sbas = it)) }
        Check("Galileo HAS", p.has) { onChange(p.copy(has = it)) }
        Text("RTCM z mobilu (NTRIP) zůstává vždy povolené.", style = MaterialTheme.typography.bodySmall)
        Check("bzučák", p.buzzer) { onChange(p.copy(buzzer = it)) }
        Text("NMEA (pro jiné aplikace; v režimu aplikace jen GGA a RMC)", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Check("GGA", p.gga) { onChange(p.copy(gga = it)) }
            Check("RMC", p.rmc) { onChange(p.copy(rmc = it)) }
            Check("GST", p.gst) { onChange(p.copy(gst = it)) }
            Check("GSA", p.gsa) { onChange(p.copy(gsa = it)) }
            Check("GSV", p.gsv) { onChange(p.copy(gsv = it)) }
        }
    }
}

@Composable
private fun Check(label: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(v, onChange)
        Text(label)
    }
}

@Composable
private fun DeviceEditor(c: RoverController) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var defName by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        c.roverDevice()?.let { r ->
            name = r.optJSONObject("data")?.optString("btName") ?: ""
            defName = r.optString("default")
        }
    }
    Text("Přístroj", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(name, { if (it.length <= 24) name = it }, label = { Text("Název Bluetooth (prázdné = $defName)") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            scope.launch {
                val err = c.saveRoverDevice(name.trim())
                saved = err == null
                msg = err?.let { "Chyba: $it" } ?: "Uloženo – platí po restartu roveru (pak spáruj znovu, pokud se název změnil)"
            }
        }) { Text("Uložit") }
        if (saved) OutlinedButton(onClick = { c.restartRover() }) { Text("Restartovat") }
    }
    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun CalEditor(c: RoverController) {
    val scope = rememberCoroutineScope()
    var cal by remember { mutableStateOf<JSONObject?>(null) }
    var volts by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cal = c.roverCal() }

    fun result(r: JSONObject?, okText: String) {
        msg = when {
            r == null -> "Rover neodpovídá"
            !r.optBoolean("ok") -> "Chyba: ${r.optString("err")}"
            else -> okText
        }
        if (r != null) cal = r
    }

    Text("Kalibrace", style = MaterialTheme.typography.titleSmall)
    cal?.let { k ->
        val g = k.optJSONArray("gyro")
        KeyValue("Bias gyra [°/s]", if (g != null && g.length() == 3) "${g.optDouble(0)} ${g.optDouble(1)} ${g.optDouble(2)}" else "–")
        KeyValue("Dělič baterie", k.optString("div", "–"))
        KeyValue("Baterie", k.optString("bat", "–") + " V" + if (k.optBoolean("batOk", true)) "" else " (chyba měření)")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(volts, { volts = it }, label = { Text("Změřené napětí [V]") }, singleLine = true,
            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        TextButton(onClick = {
            val v = volts.replace(',', '.').toDoubleOrNull() ?: return@TextButton
            scope.launch {
                busy = true
                result(c.calibrateRover(v), "Baterie zkalibrována")
                busy = false
            }
        }, enabled = !busy && volts.replace(',', '.').toDoubleOrNull() != null) { Text("Kalibrovat") }
    }
    OutlinedButton(onClick = {
        scope.launch {
            busy = true
            msg = "Kalibrace gyra – nehýbat 3 s…"
            result(c.calibrateRover(null), "Bias gyra uložen")
            busy = false
        }
    }, enabled = !busy) { Text("Kalibrace gyra (3 s v klidu)") }
    Text("Napětí baterie změř multimetrem přímo na článku, rover bez USB (nabíjení zkresluje).", style = MaterialTheme.typography.bodySmall)
    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
