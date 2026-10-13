package cz.antonin.rovergnss.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.Job
import cz.antonin.rovergnss.service.RoverController

/** Výběr aktivní lokality (v datech „job“): přepnutí, nová, přejmenování, smazání. */
@Composable
fun JobBar(c: RoverController, modifier: Modifier = Modifier) {
    val job by c.activeJob.collectAsStateWithLifecycle()
    val jobsFlow = remember { c.db.jobs().all() }
    val jobs by jobsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }   // "new" / "rename" / "delete"
    var text by remember { mutableStateOf("") }

    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Folder, contentDescription = null)
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { menu = true }) {
                Text("Lokalita: ${job?.name ?: "…"}", style = MaterialTheme.typography.titleSmall)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                jobs.forEach { j ->
                    DropdownMenuItem(
                        text = { Text(j.name + if (j.id == job?.id) "  ✓" else "") },
                        onClick = {
                            menu = false
                            c.setActiveJob(j.id)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Nová lokalita…") }, onClick = {
                    menu = false
                    text = ""
                    dialog = "new"
                })
                DropdownMenuItem(text = { Text("Přejmenovat…") }, enabled = job != null, onClick = {
                    menu = false
                    text = job?.name ?: ""
                    dialog = "rename"
                })
                DropdownMenuItem(text = { Text("Smazat lokalitu…") }, enabled = job != null && jobs.size > 1, onClick = {
                    menu = false
                    dialog = "delete"
                })
            }
        }
    }

    when (dialog) {
        "new", "rename" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (dialog == "new") "Nová lokalita" else "Přejmenovat lokalitu") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Název") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (dialog == "new") c.createJob(text) else job?.let { c.renameJob(it, text) }
                    dialog = null
                }, enabled = text.isNotBlank()) { Text("Uložit") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Zrušit") } },
        )
        "delete" -> job?.let { j: Job ->
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Smazat lokalitu?") },
                text = { Text("${j.name} – smažou se i všechny její body, měření a trasy. Nelze vrátit.") },
                confirmButton = {
                    TextButton(onClick = {
                        c.deleteJob(j)
                        dialog = null
                    }) { Text("Smazat") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Zrušit") } },
            )
        }
    }
}
