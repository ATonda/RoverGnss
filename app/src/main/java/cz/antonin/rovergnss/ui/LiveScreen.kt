package cz.antonin.rovergnss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.geo.Etrs
import cz.antonin.rovergnss.geo.Geodetic
import cz.antonin.rovergnss.geo.Krovak
import cz.antonin.rovergnss.link.Pos
import cz.antonin.rovergnss.link.Quality
import cz.antonin.rovergnss.service.RoverController

/** Poloha v ETRS89 (HAS/SBAS přepočtené z ITRF2020). */
fun Pos.etrs(): Geodetic? {
    if (!hasFix) return null
    val h = hEll ?: 0.0
    return if (itrf && timeMs != null) Etrs.itrfToEtrs(lat!!, lon!!, h, Etrs.epochYear(timeMs))
    else Geodetic(lat!!, lon!!, h)
}

@Composable
fun LiveScreen(c: RoverController) {
    val pos by c.pos.collectAsStateWithLifecycle()
    val sta by c.sta.collectAsStateWithLifecycle()
    val trail by c.trail.collectAsStateWithLifecycle()
    val rec by c.recording.collectAsStateWithLifecycle()
    val lq by c.linkQuality.collectAsStateWithLifecycle()
    var style by rememberSaveable { mutableIntStateOf(c.prefs.mapStyle) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var styleMenu by remember { mutableStateOf(false) }

    val p = pos
    val g = p?.etrs()

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapPane(
                styleIndex = style,
                pos = g?.let { doubleArrayOf(it.lat, it.lon) },
                quality = p?.quality?.code ?: 0,
                trail = trail,
                follow = follow,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    SmallFloatingActionButton(onClick = { styleMenu = true }) {
                        Icon(Icons.Filled.Layers, contentDescription = "Podklad")
                    }
                    DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                        MapStyles.NAMES.forEachIndexed { i, n ->
                            DropdownMenuItem(text = { Text(n) }, onClick = {
                                styleMenu = false
                                style = i
                                c.prefs.mapStyle = i
                            })
                        }
                    }
                }
                FilledIconToggleButton(checked = follow, onCheckedChange = { follow = it }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Sledovat polohu")
                }
            }
            ExtendedFloatingActionButton(
                onClick = { if (rec == null) c.startRecording() else c.stopRecording() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                containerColor = if (rec == null) MaterialTheme.colorScheme.primaryContainer else Color(0xFFC62828),
                contentColor = if (rec == null) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                icon = {
                    Icon(if (rec == null) Icons.Filled.FiberManualRecord else Icons.Filled.Stop, contentDescription = null)
                },
                text = { Text(rec?.let { "Stop (${it.points})" } ?: "Záznam") },
            )
        }

        // panel s údaji
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LinkMeter(lq)
            if (p == null) {
                Text("Žádná data z roveru.")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityBadge(
                    p.quality,
                    if (p.quality == Quality.HAS) (if (p.carrSoln == 2) "konv." else "float") else "",
                )
                Text("SV ${p.numSv}  PDOP ${fmt(p.pdop, 1)}")
                if (p.moving) Text("pohyb", color = Color(0xFFEF6C00))
            }
            KeyValue("hAcc / vAcc", "${fmt(p.hAcc, 3)} / ${fmt(p.vAcc, 3)} m")
            KeyValue("σ N / E / U", "${fmt(p.sN, 3)} / ${fmt(p.sE, 3)} / ${fmt(p.sU, 3)} m")
            if (p.corrAge != null) KeyValue("Stáří korekcí", "≤ ${fmt(p.corrAge, 0)} s")
            if (g != null) {
                KeyValue("φ ETRS89", dms(g.lat, true))
                KeyValue("λ ETRS89", dms(g.lon, false))
                KeyValue("h elipsoid", fmt(g.h, 3, " m"))
                val j = Krovak.fromEtrs(g.lat, g.lon, g.h)
                KeyValue("S-JTSK Y / X", "${fmt(j.y, 2)} / ${fmt(j.x, 2)}")
                KeyValue("H (u-blox geoid)", fmt(p.hMsl, 2, " m"))
                if (p.itrf) Text(
                    "Bez RTCM: přepočet ITRF2020 → ETRS89. S-JTSK zatím 7 parametry (~0,4 m).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            KeyValue("Rychlost / kurz", "${fmt(p.speed * 3.6, 1)} km/h / ${fmt(p.heading, 0)}°")
            sta?.let { s ->
                KeyValue("Baterie roveru", s.batV?.let { "${fmt(it, 2)} V (${s.batPct} %)" } ?: "–")
            }
        }
    }
}
