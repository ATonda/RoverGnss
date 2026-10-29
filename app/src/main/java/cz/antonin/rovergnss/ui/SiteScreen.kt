package cz.antonin.rovergnss.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cz.antonin.rovergnss.service.RoverController

/** Karta Body: výběr lokality a přepínač Body | Vytyčování | Objekt (náčrt půdorysu, 0.11.0). */
@Composable
fun SiteScreen(c: RoverController) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    if (sub > 2) sub = 0
    val objFull by c.objFull.collectAsStateWithLifecycle()
    val full = objFull && sub == 2
    var subMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (sub == 2) {
            // Objekt: lokalita a přepínač v jednom řádku – víc místa pro náčrt (0.11.12)
            if (!full) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                JobBar(c, Modifier.weight(1f), compact = true)
                Box {
                    TextButton(onClick = { subMenu = true }) { Text("Objekt ▾") }
                    DropdownMenu(expanded = subMenu, onDismissRequest = { subMenu = false }) {
                        listOf("Body", "Vytyčování", "Objekt").forEachIndexed { i, n ->
                            DropdownMenuItem(text = { Text(n + if (i == sub) "  ✓" else "") }, onClick = {
                                subMenu = false
                                sub = i
                            })
                        }
                    }
                }
            }
        } else {
            JobBar(c)
            TabRow(selectedTabIndex = sub) {
                Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("Body") })
                Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("Vytyčování") })
                Tab(selected = sub == 2, onClick = { sub = 2 }, text = { Text("Objekt") })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (sub) {
                1 -> StakeScreen(c)
                2 -> ObjectScreen(c)
                else -> PointsScreen(c)
            }
        }
    }
}
