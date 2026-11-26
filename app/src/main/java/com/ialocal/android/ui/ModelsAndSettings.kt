package com.ialocal.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ialocal.android.chat.ChatUiState
import com.ialocal.android.inference.InferenceSettings
import com.ialocal.android.model.InstalledModel
import com.ialocal.android.model.ModelCatalog
import java.text.NumberFormat

@Composable
internal fun ModelScreen(
    state: ChatUiState,
    modifier: Modifier,
    onDownload: () -> Unit,
    onImport: (String, String) -> Unit,
    onActivate: (String) -> Unit,
    onDelete: (InstalledModel) -> Unit,
    onCancel: () -> Unit
) {
    val recommended = ModelCatalog.recommended
    val present = state.models.any { it.sha256 == recommended.sha256 }
    val canChange = !state.loading && !state.generating && state.transfer == null
    var importDialog by rememberSaveable { mutableStateOf(false) }
    var delete by remember { mutableStateOf<InstalledModel?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!present) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(recommended.name, style = MaterialTheme.typography.titleLarge)
                    Text("Conversación en CPU · 4 bits · 1,31 GB",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Se verifica el SHA-256 antes de usarlo.")
                    Button(onClick = onDownload, enabled = canChange) { Text("Descargar Qwen3.5-2B") }
                }
            }
        }
        state.transfer?.let { transfer ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(transfer.label, style = MaterialTheme.typography.titleMedium)
                        val total = transfer.total
                        if (total != null) {
                            val fraction = (transfer.bytes.toFloat() / total).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (transfer.bytes > 0) {
                            val completed = fileSize(transfer.bytes)
                            Text(if (total == null) completed else "$completed de ${fileSize(total)}")
                        }
                        if (transfer.label != "Eliminando modelo") {
                            TextButton(onClick = onCancel) { Text("Cancelar transferencia") }
                        }
                    }
                }
            }
        }
        item { Text("Instalados", style = MaterialTheme.typography.titleLarge) }
        if (state.models.isEmpty()) item {
            Text("Aún no hay modelos en el teléfono.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.models, key = { it.sha256 }) { model ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    val selected = state.activeModel?.sha256 == model.sha256
                    Text(
                        fileSize(model.file.length()) + if (selected) " · En uso" else "",
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!selected) TextButton(onClick = { onActivate(model.sha256) }, enabled = canChange) {
                            Text("Activar")
                        }
                        TextButton(onClick = { delete = model }, enabled = canChange) { Text("Eliminar") }
                    }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Puedes importar una versión de texto Qwen3.5 en formato GGUF, incluido un modelo personalizado.")
            TextButton(onClick = { importDialog = true }, enabled = canChange) { Text("Importar GGUF") }
        }
    }

    if (importDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        var hash by rememberSaveable { mutableStateOf("") }
        val valid = hash.trim().matches(Regex("[a-fA-F0-9]{64}"))
        AlertDialog(
            onDismissRequest = { importDialog = false },
            title = { Text("Importar modelo verificado") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pega la suma SHA-256 publicada por quien te proporcionó el archivo.")
                    OutlinedTextField(
                        name, { if (it.length <= 52) name = it },
                        label = { Text("Nombre (opcional)") }, singleLine = true
                    )
                    OutlinedTextField(
                        hash, { if (it.trim().length <= 64) hash = it },
                        label = { Text("SHA-256") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            dismissButton = { TextButton(onClick = { importDialog = false }) { Text("Cancelar") } },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    onImport(name, hash)
                    importDialog = false
                }) { Text("Elegir archivo") }
            }
        )
    }

    delete?.let { model ->
        ConfirmDelete("Eliminar modelo", "Se quitará «${model.name}» del teléfono.",
            dismiss = { delete = null }, confirm = { onDelete(model); delete = null })
    }
}

@Composable
internal fun SettingsScreen(
    state: ChatUiState,
    modifier: Modifier,
    onConfigure: (InferenceSettings) -> Unit,
    onModels: () -> Unit
) {
    val settings = state.settings
    val enabled = !state.loading && !state.generating
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Memoria y contexto", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Un contexto más corto reduce el uso de RAM. Los turnos antiguos se omiten de la petición si dejan de caber.",
                Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            SelectNumber(
                "Contexto (tokens)", InferenceSettings.contexts, settings.contextTokens, enabled
            ) { context ->
                onConfigure(settings.copy(
                    contextTokens = context, outputTokens = settings.outputTokens.coerceAtMost(context / 2)
                ))
            }
        }
        item {
            SelectNumber(
                "Máximo de salida (tokens)", InferenceSettings.outputs.filter { it < settings.contextTokens },
                settings.outputTokens, enabled
            ) { onConfigure(settings.copy(outputTokens = it)) }
        }
        item {
            SelectNumber("Hilos de CPU", InferenceSettings.threadCounts, settings.threads, enabled) {
                onConfigure(settings.copy(threads = it))
            }
            Text("Prueba con menos hilos si el teléfono se calienta.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Modelos locales", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onModels) { Text("Administrar") }
            }
        }
    }
}

@Composable
private fun SelectNumber(
    title: String, choices: List<Int>, selected: Int, enabled: Boolean, changed: (Int) -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (value in choices) {
            FilterChip(
                selected = value == selected,
                onClick = { changed(value) },
                enabled = enabled,
                label = { Text(value.toString()) }
            )
        }
    }
}

private fun fileSize(bytes: Long): String {
    val giga = bytes >= 1_000_000_000
    val value = bytes.toDouble() / if (giga) 1_000_000_000 else 1_000_000
    val number = NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("es")).apply {
        maximumFractionDigits = if (giga) 2 else 0
    }
    return number.format(value) + if (giga) " GB" else " MB"
}
