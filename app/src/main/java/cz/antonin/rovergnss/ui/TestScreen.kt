package cz.antonin.rovergnss.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.link.LinkState
import cz.antonin.rovergnss.service.RoverController
import cz.antonin.rovergnss.service.TestRun
import cz.antonin.rovergnss.service.RoverTtff
import cz.antonin.rovergnss.service.TestFile
import cz.antonin.rovergnss.service.TestMeta
import cz.antonin.rovergnss.service.TestSession
import cz.antonin.rovergnss.service.TtffTarget
import cz.antonin.rovergnss.service.TtffTry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Karta Test: testovací záznamy pro EKF, doba do fixu roveru, start po vypnutí. */
@Composable
fun TestScreen(c: RoverController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TestRecordSection(c)
        TestFilesSection(c)
        RoverTtffSection(c)
        BootSection(c)
        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------------- testovací záznam
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestRecordSection(c: RoverController) {
    val run by c.test.run.collectAsStateWithLifecycle()
    val link by c.linkState.collectAsStateWithLifecycle()
    val sta by c.sta.collectAsStateWithLifecycle()
    val rec by c.recording.collectAsStateWithLifecycle()
    var sel by remember { mutableIntStateOf(0) }
    val sc = TestSession.SCENARIOS[sel]
    var carry by remember(sel) { mutableStateOf(sc.carry) }
    var orient by remember { mutableStateOf(TestSession.ORIENT.last()) }
    var note by remember { mutableStateOf("") }
    var noteDialog by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var points by remember { mutableStateOf<List<Point>>(emptyList()) }
    var pointSel by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(sc.circuit, run == null) {
        if (sc.circuit && run == null) {
            points = c.db.points().listByJob(c.ensureJob().id).filter { it.lat != null && it.lon != null && it.hEll != null }
        }
    }
    LaunchedEffect(run != null) {
        while (run != null) {
            delay(1000)
            tick++
        }
    }

    Section("Testovací záznam (EKF)") {
        val r = run
        if (r == null) {
            TestSession.SCENARIOS.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().clickable { sel = i }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sel == i, onClick = { sel = i })
                    Column {
                        Text(s.title)
                        Text(s.purpose, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Postup", style = MaterialTheme.typography.titleSmall)
            sc.procedure.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            if (sc.steps.isNotEmpty()) {
                Text(
                    "Kroky: " + sc.steps.joinToString(", ") { "${it.label} ${it.sec} s" } +
                        ". Před každým krokem odpočet s pípnutím, na začátku dlouhé pípnutí, na konci potvrzovací.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (sc.autoStopSec > 0) Text("Záznam skončí sám po ${sc.autoStopSec / 60} min.", style = MaterialTheme.typography.bodySmall)

            Text("Nošení", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(sc.carry) + TestSession.CARRY).distinct().forEach { v ->
                    FilterChip(selected = carry == v, onClick = { carry = v }, label = { Text(v) })
                }
            }
            Text("Orientace krabičky (čelo s USB) vůči směru chůze", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TestSession.ORIENT.forEach { v -> FilterChip(selected = orient == v, onClick = { orient = v }, label = { Text(v) }) }
            }
            OutlinedTextField(note, { note = it }, label = { Text("Poznámka (místo, počasí, …)") }, modifier = Modifier.fillMaxWidth())
            Text(
                "Výška antény ${antCm(c.prefs.antennaH)} (uživatelský profil). Ukládá se trasa se surovým proudem " +
                    "(IMU 60 Hz, NAV-PVT/COV/HPPOSLLH) a diagnostikou, metadata a značky zvlášť; export jako jeden ZIP.",
                style = MaterialTheme.typography.bodySmall,
            )
            val connected = link is LinkState.Connected
            val fw13 = sta?.corrCfg != null
            if (!connected) Text("Připoj rover.", color = MaterialTheme.colorScheme.error)
            else if (!fw13) Text("Rover nemá FW 0.13.0+ – rámce NAV pro EKF nepřijdou (IMU ano).", color = MaterialTheme.colorScheme.error)
            if (rec != null) Text("Běží jiný záznam trasy.", color = MaterialTheme.colorScheme.error)
            if (sc.circuit) {
                Text("Bod (lokalita, se souřadnicemi ETRS89)", style = MaterialTheme.typography.titleSmall)
                if (points.isEmpty()) Text("V lokalitě není bod s danými souřadnicemi – převezmi ho z vrstvy ČÚZK v Živě.",
                    color = MaterialTheme.colorScheme.error)
                points.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { pointSel = p.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = pointSel == p.id, onClick = { pointSel = p.id })
                        Column {
                            Text(p.name)
                            Text(listOfNotNull(p.kind.ifEmpty { null }, p.y?.let { String.format(Locale.US, "Y %.2f X %.2f", it, p.x ?: 0.0) },
                                p.bpv?.let { String.format(Locale.US, "Bpv %.3f", it) }).joinToString(", "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val point = points.firstOrNull { it.id == pointSel }
                Button(
                    onClick = { point?.let { c.test.startCircuit(sc, TestMeta(carry, orient, note.trim()), it) } },
                    enabled = connected && rec == null && point != null,
                ) { Text("Studený start a vypnout") }
            } else Button(
                onClick = { c.test.start(sc, TestMeta(carry, orient, note.trim())) },
                enabled = connected && rec == null,
            ) { Text("Spustit test") }
        } else if (r.scenario.circuit) {
            tick.let { }
            CircuitRun(c, r, onNote = {
                noteText = ""
                noteDialog = true
            })
        } else {
            tick.let { }
            val el = (System.currentTimeMillis() - r.startMs) / 1000
            Text(r.scenario.title, style = MaterialTheme.typography.titleSmall)
            KeyValue("Doba", String.format(Locale.US, "%d:%02d", el / 60, el % 60))
            KeyValue("Epoch v trase", (rec?.points ?: 0).toString())
            KeyValue("Rámce NAV z roveru", if (sta?.navRec == true) "ano" else "ne")
            when (r.phase) {
                "prep" -> Text("Připrav: ${r.info} – za ${r.remainSec} s", style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                "step" -> Text("${r.info} – ${r.remainSec} s", style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                else -> if (r.remainSec > 0) Text("${r.info}: ${r.remainSec / 60}:${String.format(Locale.US, "%02d", r.remainSec % 60)}")
                else if (r.info.isNotEmpty()) Text(r.info)
            }
            if (r.scenario.steps.isNotEmpty() && r.stepIndex >= 0) {
                Text("Krok ${(r.stepIndex + 1).coerceAtMost(r.scenario.steps.size)}/${r.scenario.steps.size}", style = MaterialTheme.typography.bodySmall)
            }
            if (r.scenario.markers.isNotEmpty()) {
                Text("Značky", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.scenario.markers.forEach { m ->
                        OutlinedButton(onClick = {
                            if (m == "poznámka") {
                                noteText = ""
                                noteDialog = true
                            } else {
                                c.test.mark(m)
                            }
                        }) { Text(m) }
                    }
                }
            }
            r.markers.takeLast(3).reversed().forEach { m ->
                val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(m.phoneMs))
                Text("$t  ${m.label}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { c.test.stop() }) { Text("Ukončit test") }
        }
    }

    if (noteDialog) AlertDialog(
        onDismissRequest = { noteDialog = false },
        title = { Text("Poznámka") },
        text = { OutlinedTextField(noteText, { noteText = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = {
                c.test.mark("poznámka: ${noteText.trim()}")
                noteDialog = false
            }, enabled = noteText.isNotBlank()) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = { noteDialog = false }) { Text("Zrušit") } },
    )
}

// ---------------------------------------------------------------------- Kontrolní okruh
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CircuitRun(c: RoverController, r: TestRun, onNote: () -> Unit) {
    val meas by c.measure.collectAsStateWithLifecycle()
    val pos by c.pos.collectAsStateWithLifecycle()
    val rec by c.recording.collectAsStateWithLifecycle()
    val sta by c.sta.collectAsStateWithLifecycle()
    var infoOpen by remember { mutableStateOf(false) }
    val phaseTitle = when (r.phase) {
        TestSession.PH_OFF -> "1. Vypnutí a zapnutí roveru"
        TestSession.PH_PLACE -> "2. Položit na bod"
        TestSession.PH_MEAS1 -> "3. Měření na bodě (30 min)"
        TestSession.PH_LOOP -> "4. Okruh"
        TestSession.PH_MEAS2 -> "5. Druhé měření na bodě (15 min)"
        else -> r.phase
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.scenario.title + (r.point?.let { " – ${it.name}" } ?: ""), style = MaterialTheme.typography.titleSmall)
            Text(phaseTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { infoOpen = true }) { Text("ⓘ", style = MaterialTheme.typography.headlineSmall) }
    }
    if (r.info.isNotEmpty()) Text(r.info, style = MaterialTheme.typography.bodyLarge)
    if (r.trackId != 0L) {
        val el = (System.currentTimeMillis() - r.startMs) / 1000
        KeyValue("Záznam", String.format(Locale.US, "%d:%02d:%02d, epoch %d", el / 3600, el / 60 % 60, el % 60, rec?.points ?: 0))
    }
    sta?.let { s ->
        KeyValue("Stav roveru", (s.motionText ?: "–") + (s.imuTemp?.let { String.format(Locale.US, ", IMU %.1f °C", it) } ?: "") +
            if (s.still == true) ", klid" else "")
    }
    when (r.phase) {
        TestSession.PH_OFF -> {
            OutlinedButton(onClick = { c.test.circuitPowered() }) { Text("Rover je zapnutý – pokračovat") }
        }
        TestSession.PH_PLACE -> {
            Button(onClick = { c.test.circuitPlaced() }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Položeno na bodě", style = MaterialTheme.typography.titleLarge)
            }
        }
        TestSession.PH_MEAS1, TestSession.PH_MEAS2 -> {
            Text(String.format(Locale.US, "%d:%02d", r.remainSec / 60, r.remainSec % 60),
                style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            if (r.phase == TestSession.PH_MEAS1) {
                KeyValue("3D fix od studeného startu", r.fixSec?.let { String.format(Locale.US, "%.1f s", it) } ?: "čekám…")
                KeyValue("Korekce od studeného startu", r.corrSec?.let { String.format(Locale.US, "%.1f s", it) } ?: "čekám…")
            }
            KeyValue("Kvalita", pos?.quality?.label ?: "–")
            pos?.let { p -> KeyValue("hAcc / vAcc", String.format(Locale.US, "%.3f / %.3f m", p.hAcc, p.vAcc)) }
            meas?.let { m ->
                KeyValue("Epoch v průměru", "${m.result?.used ?: 0} z ${m.result?.epochs ?: 0}")
                m.offset?.let { o ->
                    KeyValue("Odchylka 2D", o.d2?.let { String.format(Locale.US, "%.3f m", it) } ?: "–")
                    KeyValue("Odchylka N / E", String.format(Locale.US, "%+.3f / %+.3f m", o.dN ?: Double.NaN, o.dE ?: Double.NaN))
                    KeyValue("Odchylka výšky", o.dU?.let { String.format(Locale.US, "%+.3f m", it) } ?: "–")
                }
            }
        }
        TestSession.PH_LOOP -> {
            Text("Značky", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.scenario.markers.forEach { m ->
                    OutlinedButton(onClick = { if (m == "poznámka") onNote() else c.test.mark(m) }) { Text(m) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { c.test.circuitPlaced() }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Položeno na bodě (15 min)", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
    r.markers.takeLast(3).reversed().forEach { m ->
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(m.phoneMs))
        Text("$t  ${m.label}", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = { c.test.stop() }) { Text(if (r.trackId == 0L) "Zrušit test" else "Ukončit test") }

    if (infoOpen) AlertDialog(
        onDismissRequest = { infoOpen = false },
        title = { Text(phaseTitle) },
        text = { Text(circuitHelp(r.phase, r.point?.name ?: "bod")) },
        confirmButton = { TextButton(onClick = { infoOpen = false }) { Text("Zavřít") } },
    )
}

private fun circuitHelp(phase: String, point: String): String = when (phase) {
    TestSession.PH_OFF ->
        "X20 dostal studený start (smazání efemerid a almanachu v RAM). Teď rover vypni vypínačem. " +
            "Po zapnutí se aplikace připojí sama a spustí záznam (IMU, NAV, poloha). Když se rover neodpojil " +
            "nebo už je zapnutý, stiskni „Rover je zapnutý – pokračovat“. Rover nenabíjej."
    TestSession.PH_PLACE ->
        "Záznam běží – manipulace s krabičkou se zaznamená. Polož rover na bod $point: dnem dolů, střed krabičky " +
            "(anténa) přesně nad značkou, výška antény podle profilu. Pak stiskni „Položeno na bodě“: X20 dostane " +
            "studený start a začne měření 30 min s odpočtem. Doba do 3D fixu a do korekcí se počítá od tohoto okamžiku."
    TestSession.PH_MEAS1 ->
        "Na rover nesahej, nestoj nad ním (stín signálu). Telefon může být opodál (do ~10 m kvůli Bluetooth). " +
            "Odchylka se počítá průběžně z průměru epoch bez pohybu. Po skončení odpočtu pípnutí, měření se uloží k bodu " +
            "a začne okruh."
    TestSession.PH_LOOP ->
        "Vezmi rover, jeď kousek autem (krabička v držáku), pak pěšky po poli do lesa a zpět. Při každé změně stiskni " +
            "značku (nesu v ruce, nastupuju, jedu, vystupuju, chodím, les – vstup / výstup, klid). Telefon nos u sebe. " +
            "Na bodě rover polož stejně jako poprvé a stiskni „Položeno na bodě (15 min)“."
    TestSession.PH_MEAS2 ->
        "Druhé měření na bodě, 15 min. Na rover nesahej. Po odpočtu se test sám ukončí a uloží; ZIP najdeš v Uložených testech."
    else -> ""
}

@Composable
private fun TestFilesSection(c: RoverController) {
    val files by c.test.files.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<TestFile?>(null) }
    var toDelete by remember { mutableStateOf<TestFile?>(null) }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val t = pending
        pending = null
        if (uri != null && t != null) scope.launch {
            val n = try {
                context.contentResolver.openOutputStream(uri)?.use { c.test.exportZip(t, it) } ?: -1
            } catch (e: java.io.IOException) {
                -1
            }
            c.postMessage(if (n < 0) "Soubor nelze zapsat" else "Exportováno $n souborů do ZIP")
        }
    }
    Section("Uložené testy") {
        if (files.isEmpty()) Text("Zatím žádný test.")
        val df = remember { SimpleDateFormat("d. M. HH:mm", Locale.getDefault()) }
        files.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title)
                    Text("${df.format(Date(t.startMs))}, značek ${t.markers}, trasa ${t.trackId}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    pending = t
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(t.startMs))
                    zip.launch("RG_TEST_${stamp}_${TestSession.slug(t.title)}.zip")
                }) { Text("ZIP") }
                TextButton(onClick = { toDelete = t }) { Text("Smazat") }
            }
        }
        Text("ZIP obsahuje test.json (scénář, metadata, značky, geometrie IMU), body.csv, diag.csv a raw.log. " +
            "Smazání odstraní jen metadata testu, trasa zůstává v Body → Trasy.", style = MaterialTheme.typography.bodySmall)
    }
    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Smazat test?") },
            text = { Text("${t.title} – metadata a značky. Trasa zůstane.") },
            confirmButton = {
                TextButton(onClick = {
                    c.test.delete(t)
                    toDelete = null
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Zrušit") } },
        )
    }
}

// ---------------------------------------------------------------------- doba do fixu roveru
@Composable
private fun RoverTtffSection(c: RoverController) {
    val st by c.roverTtff.state.collectAsStateWithLifecycle()
    val link by c.linkState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reps by remember { mutableStateOf(st.reps.toString()) }
    var limit by remember { mutableStateOf(st.limitSec.toString()) }
    var pause by remember { mutableStateOf(st.pauseSec.toString()) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { c.roverTtff.csv(it) } != null
            } catch (e: java.io.IOException) {
                false
            }
            c.postMessage(if (ok) "Exportováno" else "Soubor nelze zapsat")
        }
    }
    fun cfg(kind: String = st.kind, target: TtffTarget = st.target) =
        c.roverTtff.configure(kind, target, reps.toIntOrNull() ?: st.reps, limit.toIntOrNull() ?: st.limitSec, pause.toIntOrNull() ?: st.pauseSec)

    Section("Doba do fixu roveru") {
        Text("Restart příjmu X20 příkazem a čas do 3D fixu, korekcí, float a RTK fix. Horký start vynuluje i konvergenci HAS.",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("hot", "warm", "cold").forEach { k ->
                FilterChip(selected = st.kind == k, onClick = { cfg(kind = k) }, enabled = !st.running,
                    label = { Text(RoverTtff.kindText(k)) })
            }
        }
        Text("Cíl pokusu", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TtffTarget.entries.forEach { t ->
                FilterChip(selected = st.target == t, onClick = { cfg(target = t) }, enabled = !st.running, label = { Text(t.label) })
            }
        }
        Row {
            OutlinedTextField(reps, { reps = it; cfg() }, label = { Text("Pokusů") }, singleLine = true, enabled = !st.running,
                modifier = Modifier.width(96.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(limit, { limit = it; cfg() }, label = { Text("Limit [s]") }, singleLine = true, enabled = !st.running,
                modifier = Modifier.width(110.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(pause, { pause = it; cfg() }, label = { Text("Pauza [s]") }, singleLine = true, enabled = !st.running,
                modifier = Modifier.width(110.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!st.running) Button(onClick = { cfg(); c.roverTtff.start() }, enabled = link is LinkState.Connected) { Text("Spustit") }
            else OutlinedButton(onClick = { c.roverTtff.stop() }) { Text("Zastavit") }
            OutlinedButton(onClick = {
                csv.launch("RG_TTFF_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv")
            }, enabled = st.done.isNotEmpty() || c.roverTtff.boots.value.isNotEmpty()) { Text("CSV") }
        }
        if (st.status.isNotEmpty()) Text(st.status, style = MaterialTheme.typography.bodySmall)
        st.current?.let { TtffRow(it, live = true) }
        st.done.forEach { TtffRow(it) }
        if (st.done.size >= 2) {
            fun stat(sel: (TtffTry) -> Long?): String {
                val v = st.done.filter { it.invalid == null }.mapNotNull(sel).sorted()
                if (v.isEmpty()) return "–"
                return "${sec(v.first())} / ${sec(v[v.size / 2])} / ${sec(v.last())} s (${v.size}×)"
            }
            Text("min / medián / max", style = MaterialTheme.typography.titleSmall)
            KeyValue("3D fix", stat { it.fix3d })
            KeyValue("korekce", stat { it.corr })
            KeyValue("float", stat { it.float })
            KeyValue("RTK fix", stat { it.rtkFix })
        }
    }
}

private fun sec(ms: Long) = String.format(Locale.US, "%.1f", ms / 1000.0).replace('.', ',')

@Composable
private fun TtffRow(t: TtffTry, live: Boolean = false) {
    fun v(x: Long?) = x?.let { sec(it) } ?: "–"
    Text(
        (if (live) "▶ " else "") + "${RoverTtff.kindText(t.kind)}: 3D ${v(t.fix3d)} s, kor. ${v(t.corr)} s, float ${v(t.float)} s, " +
            "fix ${v(t.rtkFix)} s" + (t.firstSv?.let { ", SV $it" } ?: "") + (if (t.timeout) " – limit" else "") +
            (t.invalid?.let { " – NEPLATNÉ: $it" } ?: "") + (if (live && t.lostMs == null && t.invalid == null) " – čekám na ztrátu fixu" else ""),
        style = MaterialTheme.typography.bodySmall,
    )
}

// ---------------------------------------------------------------------- start po vypnutí
@Composable
private fun BootSection(c: RoverController) {
    val boots by c.roverTtff.boots.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf(false) }
    Section("Start po vypnutí (záloha X20)") {
        Text(
            "Rover (FW 0.13.0+) hlásí dobu od zapnutí do 3D fixu a korekcí. Doba vypnutí = od posledních dat z roveru " +
                "v aplikaci do jeho zapnutí. Postup: rover vypni na 1 h, 4 h, 12 h, zapni a připoj aplikaci (do 30 min).",
            style = MaterialTheme.typography.bodySmall,
        )
        if (boots.isEmpty()) Text("Zatím žádný start.")
        val df = remember { SimpleDateFormat("d. M. HH:mm", Locale.getDefault()) }
        fun d(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it).replace('.', ',') } ?: "–"
        boots.take(20).forEach { b ->
            Text(
                "${df.format(Date(b.atMs))}: vypnuto ${b.offMin?.let { off -> if (off >= 120) d(off / 60) + " h" else d(off) + " min" } ?: "?"}" +
                    " → 3D ${d(b.fixS)} s, kor. ${d(b.corrS)} s, float ${d(b.floatS)} s, fix ${d(b.rtkS)} s",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (boots.isNotEmpty()) TextButton(onClick = { ask = true }) { Text("Smazat seznam") }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Smazat seznam startů?") },
        confirmButton = {
            TextButton(onClick = {
                c.roverTtff.clearBoots()
                ask = false
            }) { Text("Smazat") }
        },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Zrušit") } },
    )
}
