package cz.antonin.rovergnss.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.link.LinkState
import cz.antonin.rovergnss.ntrip.NtripProfile
import cz.antonin.rovergnss.ntrip.NtripState
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(c: RoverController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RoverSection(c)
        NtripSection(c)
        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------------- rover
@SuppressLint("MissingPermission")
@Composable
private fun RoverSection(c: RoverController) {
    val link by c.linkState.collectAsStateWithLifecycle()
    val info by c.info.collectAsStateWithLifecycle()
    val sta by c.sta.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(c.prefs.roverAddress) }
    val devices = remember(link) { c.link.bondedDevices() }

    Section("Rover") {
        when (val l = link) {
            LinkState.Idle -> {
                if (!c.link.isEnabled) Text("Bluetooth je vypnutý.", color = MaterialTheme.colorScheme.error)
                if (devices.isEmpty()) {
                    Text("Žádné spárované zařízení. Rover (ROVER-xxxx) nejdřív spáruj v nastavení Bluetooth telefonu.")
                }
                devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = d.address },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == d.address, onClick = { selected = d.address })
                        Column {
                            Text(d.name ?: "?")
                            Text(d.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Button(
                    onClick = { selected?.let { c.connect(it) } },
                    enabled = selected != null && c.link.isEnabled,
                ) { Text("Připojit") }
            }
            is LinkState.Failed -> {
                Text(l.error, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { c.disconnect() }) { Text("Zpět") }
            }
            is LinkState.Connecting -> {
                Text("Připojuji ${l.name} (pokus ${l.attempt})…")
                l.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { c.disconnect() }) { Text("Zrušit") }
            }
            is LinkState.Connected -> {
                Text("Připojeno: ${l.name}", style = MaterialTheme.typography.bodyLarge)
                val lq by c.linkQuality.collectAsStateWithLifecycle()
                LinkMeter(lq)
                if (lq.lostTotal > 0) KeyValue("Ztracené epochy", lq.lostTotal.toString())
                info?.let { i ->
                    KeyValue("Firmware", "${i.fw} (protokol ${i.proto})")
                    KeyValue("X20", listOfNotNull(i.x20, i.x20Fw).joinToString(", ").ifEmpty { "neodpovídá" })
                    KeyValue("IMU", if (i.imu) "ano" else "ne")
                }
                sta?.let { s ->
                    KeyValue("Baterie", s.batV?.let { "${fmt(it, 2)} V (${s.batPct} %)" } ?: "–")
                    KeyValue("Volná RAM", "${s.freeHeap / 1024} kB")
                    if (s.btBacklog != null) {
                        KeyValue("Konec epochy X20", if (s.eoe) "ano (HPPOSLLH v každé epoše)" else "ne")
                        KeyValue("Režim aplikace", if (s.appMode) "ano (NMEA jen GGA, RMC)" else "ne")
                    }
                    ProfilePicker(c, s.profile, s.profileCount, s.profileName)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { c.disconnect() }) { Text("Odpojit") }
                    X20ResetMenu(c)
                }
            }
        }
        ExitButton(c)
    }
}

/** Úplné ukončení aplikace (služba na popředí jinak drží spojení i po zavření okna). */
@Composable
private fun ExitButton(c: RoverController) {
    val rec by c.recording.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { if (rec != null) ask = true else c.shutdown() }) {
        Text("Ukončit aplikaci", color = MaterialTheme.colorScheme.error)
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("Ukončit aplikaci?") },
            text = { Text("Běží záznam trasy – uloží se a ukončí.") },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    c.shutdown()
                }) { Text("Uložit a ukončit", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Zrušit") } },
        )
    }
}

@Composable
private fun ProfilePicker(c: RoverController, active: Int, count: Int, name: String) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Profil: ${active + 1}/$count $name", modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }, enabled = count > 1) { Text("Změnit") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (i in 0 until count) {
                    DropdownMenuItem(
                        text = { Text("Profil ${i + 1}" + if (i == active) " (aktivní)" else "") },
                        onClick = {
                            open = false
                            c.setProfile(i)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun X20ResetMenu(c: RoverController) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Restart X20") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("hot" to "horký start", "warm" to "teplý start", "cold" to "studený start").forEach { (k, t) ->
                DropdownMenuItem(text = { Text(t) }, onClick = {
                    open = false
                    c.resetX20(k)
                })
            }
        }
    }
}

// ---------------------------------------------------------------------- NTRIP
@Composable
private fun NtripSection(c: RoverController) {
    val state by c.ntripState.collectAsStateWithLifecycle()
    val sta by c.sta.collectAsStateWithLifecycle()
    val link by c.linkState.collectAsStateWithLifecycle()
    var profiles by remember { mutableStateOf(c.prefs.ntripProfiles) }
    var index by remember { mutableIntStateOf(c.prefs.ntripIndex.coerceIn(0, maxOf(0, profiles.size - 1))) }
    var edit by remember { mutableStateOf(profiles.getOrNull(index) ?: NtripProfile(name = "Nový")) }
    var editing by remember { mutableStateOf(profiles.isEmpty()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    Section("Korekce NTRIP") {
        // výběr profilu
        var open by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                profiles.getOrNull(index)?.let { "${it.name} – ${it.host}/${it.mount}" } ?: "žádný profil",
                modifier = Modifier.weight(1f),
            )
            Box {
                TextButton(onClick = { open = true }, enabled = profiles.isNotEmpty()) { Text("Vybrat") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    profiles.forEachIndexed { i, p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = {
                            open = false
                            index = i
                            c.prefs.ntripIndex = i
                            edit = p
                            editing = false
                        })
                    }
                }
            }
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Skrýt" else "Upravit") }
        }

        if (editing) NtripEditor(c, edit, onChange = { edit = it },
            onSave = {
                val l = profiles.toMutableList()
                if (index < l.size) l[index] = edit else {
                    l += edit
                    index = l.size - 1
                }
                profiles = l
                c.prefs.ntripProfiles = l
                c.prefs.ntripIndex = index
                editing = false
            },
            onAdd = {
                edit = NtripProfile(name = "Nový")
                index = profiles.size
            },
            onDelete = {
                if (index < profiles.size) {
                    val l = profiles.toMutableList().also { it.removeAt(index) }
                    profiles = l
                    c.prefs.ntripProfiles = l
                    index = 0
                    c.prefs.ntripIndex = 0
                    edit = l.firstOrNull() ?: NtripProfile(name = "Nový")
                }
            })

        // stav
        tick.let { }
        when (val s = state) {
            NtripState.Off -> Text("Vypnuto")
            is NtripState.Connecting -> Text("Připojuji (pokus ${s.attempt})" + (s.lastError?.let { " – $it" } ?: ""))
            is NtripState.Streaming -> {
                val age = (System.currentTimeMillis() - c.ntrip.lastDataMs) / 1000.0
                Text("Příjem z ${s.mount}")
                KeyValue("Přijato", "${c.ntrip.bytes.get() / 1024} kB, před ${fmt(age, 1)} s")
                KeyValue("Rámce RTCM", "${c.rtcmFramer.frames} (chybné ${c.rtcmFramer.badCrc}), poslední typ ${c.rtcmFramer.lastType}")
            }
            is NtripState.Failed -> Text("Chyba: ${s.error}", color = MaterialTheme.colorScheme.error)
        }
        sta?.let { s ->
            if (s.rtcmOk > 0) KeyValue("V roveru", "RTCM ${s.rtcmOk} (chybné ${s.rtcmBad}), stáří ${fmt(s.rtcmAge, 1)} s")
        }
        if (c.link.txDropped.get() > 0) KeyValue("Zahozeno (BT)", c.link.txDropped.get().toString())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val p = profiles.getOrNull(index)
            if (state is NtripState.Off || state is NtripState.Failed) {
                Button(onClick = { p?.let { c.startNtrip(it) } }, enabled = p != null && link is LinkState.Connected) {
                    Text("Spustit")
                }
            } else {
                OutlinedButton(onClick = { c.stopNtrip() }) { Text("Zastavit") }
            }
        }
        if (link !is LinkState.Connected) Text("Korekce lze spustit po připojení roveru.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NtripEditor(
    c: RoverController,
    p: NtripProfile,
    onChange: (NtripProfile) -> Unit,
    onSave: () -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mounts by remember { mutableStateOf<List<String>>(emptyList()) }
    var mountMenu by remember { mutableStateOf(false) }
    var mountMsg by remember { mutableStateOf<String?>(null) }

    OutlinedTextField(p.name, { onChange(p.copy(name = it)) }, label = { Text("Název") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Row {
        OutlinedTextField(p.host, { onChange(p.copy(host = it.trim())) }, label = { Text("Caster") }, singleLine = true,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(p.port.toString(), { onChange(p.copy(port = it.toIntOrNull() ?: p.port)) },
            label = { Text("Port") }, singleLine = true, modifier = Modifier.width(96.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(p.mount, { onChange(p.copy(mount = it.trim())) }, label = { Text("Mountpoint") },
            singleLine = true, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = {
                scope.launch {
                    mountMsg = "načítám…"
                    mounts = try {
                        c.ntrip.fetchMounts(p)
                    } catch (e: Exception) {
                        mountMsg = e.message ?: "chyba"
                        emptyList()
                    }
                    if (mounts.isNotEmpty()) {
                        mountMsg = null
                        mountMenu = true
                    } else if (mountMsg == "načítám…") mountMsg = "caster nevrátil seznam"
                }
            }, enabled = p.host.isNotEmpty()) { Text("Seznam") }
            DropdownMenu(expanded = mountMenu, onDismissRequest = { mountMenu = false }) {
                mounts.forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = {
                        mountMenu = false
                        onChange(p.copy(mount = m))
                    })
                }
            }
        }
    }
    mountMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Row {
        OutlinedTextField(p.user, { onChange(p.copy(user = it.trim())) }, label = { Text("Jméno") }, singleLine = true,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(p.pass, { onChange(p.copy(pass = it)) }, label = { Text("Heslo") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(p.ggaSec.toString(), { onChange(p.copy(ggaSec = it.toIntOrNull()?.coerceIn(0, 600) ?: p.ggaSec)) },
            label = { Text("GGA [s], 0 = ne") }, singleLine = true, modifier = Modifier.width(150.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = p.v2, onCheckedChange = { onChange(p.copy(v2 = it)) })
        Text("NTRIP 2.0")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = p.host.isNotEmpty() && p.mount.isNotEmpty()) { Text("Uložit") }
        OutlinedButton(onClick = onAdd) { Text("Nový") }
        OutlinedButton(onClick = onDelete) { Text("Smazat") }
    }
}
