package cz.antonin.rovergnss.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cz.antonin.rovergnss.service.RoverController

/** Karta Body: výběr lokality a přepínač Body | Vytyčování (záznam trasy jen v testech – 0.10.0). */
@Composable
fun SiteScreen(c: RoverController) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    if (sub > 1) sub = 0
    Column(Modifier.fillMaxSize()) {
        JobBar(c)
        TabRow(selectedTabIndex = sub) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("Body") })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("Vytyčování") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (sub) {
                1 -> StakeScreen(c)
                else -> PointsScreen(c)
            }
        }
    }
}
