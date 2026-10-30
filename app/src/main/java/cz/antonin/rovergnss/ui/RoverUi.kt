package cz.antonin.rovergnss.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.service.RoverController
import cz.antonin.rovergnss.service.TargetSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class Tab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Živě", Icons.Filled.Map),
    Tab("Body", Icons.Filled.Place),
    Tab("Telefon", Icons.Filled.PhoneAndroid),
    Tab("Test", Icons.Filled.Science),
    Tab("Auto", Icons.Filled.DirectionsCar),
    Tab("Nastavení", Icons.Filled.Settings),
)

private const val TAB_AUTO = 4

private val Colors = lightColorScheme(
    primary = Color(0xFF1B3A4B),
    secondary = Color(0xFF2E7D32),
    tertiary = Color(0xFFFFC107),
)

@Composable
fun RoverUi(controller: RoverController, permissionsOk: Boolean, onRequestPermissions: () -> Unit) {
    MaterialTheme(colorScheme = Colors) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var mapFull by rememberSaveable { mutableStateOf(controller.prefs.mapFull) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            // hlášení 3 s (0.11.16; dřív výchozí krátké ~4–5 s)
            controller.messages.collect { m ->
                val shown = launch { snackbar.showSnackbar(m, duration = SnackbarDuration.Indefinite) }
                kotlinx.coroutines.delay(3000)
                shown.cancel()
            }
        }
        // navigace k bodu a začátek měření přepnou na mapu
        LaunchedEffect(Unit) {
            controller.navTarget.collect { if (it != null) tab = 0 }
        }
        LaunchedEffect(Unit) {
            controller.measure.map { it != null && !it.fromObject }.distinctUntilChanged().collect { if (it) tab = 0 }
        }
        var target by rememberSaveable { mutableIntStateOf(-1) }   // terč: -1 zavřený, jinak TargetSource
        // odchod ze záložky Auto při běžícím měření rychlosti: dotaz Ukončit / Pokračovat (0.9.0)
        var leaveTo by remember { mutableIntStateOf(-1) }
        val speedOn by controller.speedActive.collectAsStateWithLifecycle()
        val objFull by controller.objFull.collectAsStateWithLifecycle()
        fun goTab(i: Int) {
            if (tab == TAB_AUTO && i != TAB_AUTO && speedOn) leaveTo = i else tab = i
        }
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!(tab == 0 && mapFull) && !(tab == 1 && objFull)) NavigationBar {
                    TABS.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { goTab(i) },
                            icon = { Icon(t.icon, contentDescription = t.title) },
                            label = { Text(t.title) },
                        )
                    }
                }
            },
        ) { inner ->
          Column(Modifier.padding(inner).fillMaxSize()) {
            if (permissionsOk) MeasureBanner(controller, onTarget = { target = TargetSource.MEASURE })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!permissionsOk) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Pro spojení s roverem je potřeba oprávnění „Zařízení v okolí“ (Bluetooth).")
                        Button(onClick = onRequestPermissions, modifier = Modifier.padding(top = 12.dp)) {
                            Text("Povolit")
                        }
                    }
                } else {
                    when (tab) {
                        0 -> LiveScreen(controller, mapFull, onTarget = { target = TargetSource.LIVE }) {
                            mapFull = it
                            controller.prefs.mapFull = it
                        }
                        1 -> SiteScreen(controller)
                        2 -> PhoneScreen(controller)
                        3 -> TestScreen(controller)
                        TAB_AUTO -> AutoScreen(controller)
                        5 -> SettingsScreen(controller)
                    }
                }
            }
          }
        }
        if (target >= 0) TargetScreen(controller, target, onClose = { target = -1 })
        if (leaveTo >= 0) androidx.compose.material3.AlertDialog(
            onDismissRequest = { leaveTo = -1 },
            title = { Text("Měření rychlosti běží") },
            text = {
                Text(
                    "Ukončit měření (rover se vrátí na svůj profil), nebo pokračovat?\n\n" +
                        "Při pokračování: rover zůstává na 10 Hz s modelem automotive a bez rámců NAV a satelitů každou " +
                        "sekundu; měření kontrolního bodu a testy nejdou spustit, záznam trasy poběží v tomto režimu " +
                        "(2× víc dat, chůze s modelem automotive)."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    controller.stopSpeed()
                    tab = leaveTo
                    leaveTo = -1
                }) { Text("Ukončit") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    tab = leaveTo
                    leaveTo = -1
                }) { Text("Pokračovat") }
            },
        )
        // průměr uložený v telefonu při minulém běhu: pokračovat / uložit jako trasu / smazat
        val restore by controller.liveRestore.collectAsStateWithLifecycle()
        restore?.let { r -> LiveRestoreDialog(r, onAction = { controller.restoreLive(it) }) }
        }
    }
}

@Composable
private fun LiveRestoreDialog(r: cz.antonin.rovergnss.service.LiveRestore, onAction: (Int) -> Unit) {
    val df = java.text.SimpleDateFormat("d. M. HH:mm", java.util.Locale.getDefault())
    val sec = ((r.lastMs - r.startMs) / 1000).coerceAtLeast(0)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text("Uložený průměr") },
        text = {
            Text(
                "Průměr z minulého běhu aplikace: ${df.format(java.util.Date(r.startMs))} → ${df.format(java.util.Date(r.lastMs))} " +
                    String.format(java.util.Locale.US, "(%d:%02d:%02d)", sec / 3600, sec / 60 % 60, sec % 60) +
                    ", ${r.epochs} epoch.\n\nPokračovat: rover musí stát na stejném místě – jinak se průměr po první epoše vynuluje."
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                androidx.compose.material3.TextButton(onClick = { onAction(RoverController.RestoreAction.CONTINUE) }) { Text("Pokračovat") }
                androidx.compose.material3.TextButton(onClick = { onAction(RoverController.RestoreAction.SAVE_TRACK) }) { Text("Uložit jako trasu a smazat") }
                androidx.compose.material3.TextButton(onClick = { onAction(RoverController.RestoreAction.DELETE) }) { Text("Smazat") }
            }
        },
    )
}
