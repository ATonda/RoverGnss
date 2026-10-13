package cz.antonin.rovergnss.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cz.antonin.rovergnss.service.RoverController
import cz.antonin.rovergnss.service.TargetSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class Tab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Živě", Icons.Filled.Map),
    Tab("Body", Icons.Filled.Place),
    Tab("Nastavení", Icons.Filled.Settings),
)

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
            controller.messages.collect { snackbar.showSnackbar(it) }
        }
        // navigace k bodu a začátek měření přepnou na mapu
        LaunchedEffect(Unit) {
            controller.navTarget.collect { if (it != null) tab = 0 }
        }
        LaunchedEffect(Unit) {
            controller.measure.map { it != null }.distinctUntilChanged().collect { if (it) tab = 0 }
        }
        var target by rememberSaveable { mutableIntStateOf(-1) }   // terč: -1 zavřený, jinak TargetSource
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!(tab == 0 && mapFull)) NavigationBar {
                    TABS.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
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
                        2 -> SettingsScreen(controller)
                    }
                }
            }
          }
        }
        if (target >= 0) TargetScreen(controller, target, onClose = { target = -1 })
        }
    }
}
