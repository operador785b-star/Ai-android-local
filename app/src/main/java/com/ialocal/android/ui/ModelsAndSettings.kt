package com.ialocal.android.ui

import android.content.ClipData

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ialocal.android.R
import com.ialocal.android.chat.AppearanceMode
import com.ialocal.android.chat.AppearanceSettings
import com.ialocal.android.chat.ChatUiState
import com.ialocal.android.inference.InferenceSettings
import com.ialocal.android.model.InstalledModel
import com.ialocal.android.model.ModelCatalog
import com.ialocal.android.model.ModelAvailability
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun ModelScreen(
    state: ChatUiState,
    modifier: Modifier,
    onDownload: (String) -> Unit,
    onImport: (String) -> Unit,
    onActivate: (String) -> Unit,
    onDelete: (InstalledModel) -> Unit,
    onCancel: () -> Unit
) {
    val colors = LocalGlass.current
    val available = ModelCatalog.all.filter { model ->
        model.availability is ModelAvailability.Ready && state.models.none { it.sha256 == model.sha256 && it.profile != null }
    }
    val canChange = !state.loading && !state.generating && state.transfer == null
    var importDialog by rememberSaveable { mutableStateOf(false) }
    var delete by remember { mutableStateOf<InstalledModel?>(null) }

    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 590.dp).fillMaxSize().testTag("biblioteca-modelos"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Biblioteca local", style = MaterialTheme.typography.headlineMedium)
                Text("Elige qué modelo usar en el teléfono.", Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = colors.softInk)
            }
            items(available, key = { it.sha256 }) { model ->
                val title = model.name.substringBefore(" · ")
                PreferenceCard(title, "Q4_K_M · ${fileSize(model.bytes)} · ${model.license}", Icons.Default.Build,
                    tone = GlassTone.TINTED) {
                    Text("La descarga se verifica con SHA-256.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.softInk)
                    GlassButton({ onDownload(model.sha256) }, Modifier.fillMaxWidth(), enabled = canChange) {
                        Text("Descargar $title")
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
            state.transfer?.let { transfer ->
                item {
                    PreferenceCard(transfer.label, null, Icons.Default.Info) {
                        val total = transfer.total
                        if (total != null) {
                            val fraction = (transfer.bytes.toFloat() / total).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (transfer.bytes > 0) {
                            val completed = fileSize(transfer.bytes)
                            val fraction = total?.let { (transfer.bytes.toDouble() / it).coerceIn(0.0, 1.0) }
                            val percentage = fraction?.let { NumberFormat.getPercentInstance(Spanish).format(it) }
                            Text(if (total == null) completed else "$completed de ${fileSize(total)} · $percentage")
                        }
                        if (transfer.label != "Eliminando modelo") {
                            GlassButton(onCancel, primary = false) { Text("Cancelar transferencia") }
                        }
                    }
                }
            }
            item { Text("Instalados", style = MaterialTheme.typography.titleLarge) }
            if (state.models.isEmpty()) item {
                GlassSurface(Modifier.fillMaxWidth(), tone = GlassTone.QUIET) {
                    Text("Aún no hay modelos en el teléfono.", Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.softInk)
                }
            }
            items(state.models, key = { it.sha256 }) { model ->
                val selected = state.activeModel?.sha256 == model.sha256
                val status = when {
                    selected -> "En uso"
                    model.profile == null -> "Motor incompatible"
                    else -> "Instalado"
                }
                PreferenceCard(
                    model.name, "${fileSize(model.file.length())} · $status",
                    if (selected) Icons.Default.Check else Icons.Default.Build
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!selected && model.profile != null) GlassButton({ onActivate(model.sha256) }, enabled = canChange) {
                            Text("Activar")
                        }
                        GlassButton({ delete = model }, primary = false, enabled = canChange) {
                            Text("Eliminar")
                        }
                    }
                }
            }
            item {
                PreferenceCard("Importar modelo", "Archivo GGUF local", Icons.Default.Add) {
                    GlassButton({ importDialog = true }, Modifier.fillMaxWidth(), primary = false, enabled = canChange) {
                        Text("Importar GGUF")
                    }
                }
            }
            if (ModelCatalog.candidates.isNotEmpty()) item {
                Text("Candidatos", style = MaterialTheme.typography.titleLarge)
            }
            items(ModelCatalog.candidates, key = { it.sha256 }) { model ->
                val candidate = model.availability as ModelAvailability.Candidate
                PreferenceCard(
                    model.name, "Q4_K_M · ${fileSize(model.bytes)} · ${model.license}", Icons.Default.Info
                ) {
                    Text("Requiere un motor compatible con ${candidate.architecture}.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.softInk)
                }
            }
        }
    }

    if (importDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importDialog = false },
            title = { Text("Importar modelo GGUF") },
            text = {
                OutlinedTextField(name, { if (it.length <= 52) name = it },
                    label = { Text("Nombre (opcional)") }, singleLine = true)
            },
            dismissButton = { TextButton(onClick = { importDialog = false }) { Text("Cancelar") } },
            confirmButton = {
                TextButton(onClick = {
                    onImport(name)
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
    onAppearance: (AppearanceSettings) -> Unit,
    onModels: () -> Unit
) {
    val colors = LocalGlass.current
    val settings = state.settings
    val appearance = state.appearance
    val enabled = !state.loading && !state.generating
    var license by rememberSaveable { mutableStateOf<Int?>(null) }
    val entry = revealProgress()
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 590.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            item {
                PreferenceCard("Apariencia", "Tema y movimiento", Icons.Default.Face,
                    modifier = Modifier.glassReveal(entry, 0)) {
                    ChoiceStrip(
                        AppearanceMode.entries, appearance.mode, enabled = true,
                        label = { it.label }
                    ) { onAppearance(appearance.copy(mode = it)) }
                    HorizontalDivider(color = colors.divider)
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Reducir animaciones", style = MaterialTheme.typography.titleSmall)
                            Text("Limita las transiciones y el movimiento al pulsar.",
                                style = MaterialTheme.typography.bodySmall, color = colors.softInk)
                        }
                        GlassSwitch(appearance.reduceMotion, "Reducir animaciones") {
                            onAppearance(appearance.copy(reduceMotion = it))
                        }
                    }
                }
            }
            item {
                PreferenceCard("Memoria y contexto", "Ajusta la carga de inferencia local.", Icons.Default.Build,
                    modifier = Modifier.glassReveal(entry, 1)) {
                    NumberPreference("Contexto · tokens", InferenceSettings.contexts, settings.contextTokens, enabled) {
                        onConfigure(settings.copy(contextTokens = it,
                            outputTokens = settings.outputTokens.coerceAtMost(it / 2)))
                    }
                    Text("Un contexto más corto reduce el uso de memoria.",
                        style = MaterialTheme.typography.bodySmall, color = colors.softInk)
                    HorizontalDivider(color = colors.divider)
                    NumberPreference("Máximo de salida · tokens",
                        InferenceSettings.outputs.filter { it < settings.contextTokens }, settings.outputTokens, enabled) {
                        onConfigure(settings.copy(outputTokens = it))
                    }
                    HorizontalDivider(color = colors.divider)
                    NumberPreference("Hilos de CPU", InferenceSettings.threadCounts, settings.threads, enabled) {
                        onConfigure(settings.copy(threads = it))
                    }
                }
            }
            item {
                PreferenceCard("Modelos locales", state.activeModel?.name ?: "Sin modelo activo", Icons.Default.Add,
                    modifier = Modifier.glassReveal(entry, 2)) {
                    GlassButton(onModels, Modifier.fillMaxWidth(), primary = false) {
                        Text("Administrar modelos")
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
            item {
                PreferenceCard("Diagnóstico local", "Tokens/s, latencia, pico RSS y resultado", Icons.Default.Info,
                    modifier = Modifier.glassReveal(entry, 3)) {
                    if (state.diagnostics.isBlank()) {
                        Text("Las mediciones aparecerán después de generar una respuesta.",
                            style = MaterialTheme.typography.bodyMedium, color = colors.softInk)
                    } else {
                        val clipboard = LocalClipboard.current
                        val scope = rememberCoroutineScope()
                        GlassButton({
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Diagnóstico", state.diagnostics)))
                            }
                        }, Modifier.fillMaxWidth(), primary = false) { Text("Copiar diagnóstico JSON") }
                    }
                }
            }
            item {
                PreferenceCard("Licencias", null, Icons.Default.Info, modifier = Modifier.glassReveal(entry, 4)) {
                    TextButton(onClick = { license = R.raw.llama_license }) { Text("llama.cpp · MIT") }
                    TextButton(onClick = { license = R.raw.qwen_license }) { Text("Qwen3.5 · Apache 2.0") }
                    TextButton(onClick = { license = R.raw.manrope_license }) { Text("Manrope · SIL OFL 1.1") }
                }
            }
        }
    }
    license?.let { resource ->
        val resources = LocalContext.current.resources
        val text = remember(resource) { resources.openRawResource(resource).bufferedReader().use { it.readText() } }
        AlertDialog(
            onDismissRequest = { license = null },
            title = { Text("Licencia") },
            text = { Text(text, Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { license = null }) { Text("Cerrar") } }
        )
    }
}

@Composable
private fun PreferenceCard(
    title: String,
    description: String?,
    icon: ImageVector,
    tone: GlassTone = GlassTone.STANDARD,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalGlass.current
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(25.dp), tone = tone) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Box(
                    Modifier.size(40.dp).background(
                        Brush.linearGradient(listOf(
                            Violet.copy(alpha = if (colors.dark) 0.28f else 0.16f),
                            BrightBlue.copy(alpha = if (colors.dark) 0.20f else 0.13f)
                        )), RoundedCornerShape(13.dp)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = colors.accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.softInk) }
                }
            }
            content()
        }
    }
}

@Composable
private fun NumberPreference(title: String, choices: List<Int>, selected: Int, enabled: Boolean, changed: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        ChoiceStrip(choices, selected, enabled, label = Int::toString, changed = changed)
    }
}

@Composable
private fun <T> ChoiceStrip(choices: List<T>, selected: T, enabled: Boolean, label: (T) -> String, changed: (T) -> Unit) {
    val colors = LocalGlass.current
    GlassSurface(Modifier.fillMaxWidth().selectableGroup(), shape = RoundedCornerShape(17.dp), tone = GlassTone.QUIET) {
        Row(Modifier.fillMaxWidth().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (value in choices) {
                val active = value == selected
                val press = remember { MutableInteractionSource() }
                val highlight = animateFloatAsState(
                    if (active) 1f else 0f,
                    if (LocalReduceMotion.current) snap() else tween(210, easing = GlassMotion.glide),
                    label = "opción seleccionada"
                )
                GlassSurface(
                    Modifier.weight(1f).glassPress(press, enabled), shape = RoundedCornerShape(14.dp),
                    tone = GlassTone.QUIET
                ) {
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = highlight.value }.background(
                        Brush.linearGradient(if (colors.dark) listOf(Color(0xFF413169), Color(0xFF27385A))
                            else listOf(Color(0xFFEBE2FF), Color(0xFFDFEAFF)))
                    ))
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(active, interactionSource = press, indication = null,
                                enabled = enabled, role = Role.RadioButton) { changed(value) }
                            .padding(horizontal = 3.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label(value), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                            color = when {
                                !enabled -> colors.softInk
                                active -> colors.accent
                                else -> colors.ink
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassSwitch(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalGlass.current
    val progress = animateFloatAsState(
        if (checked) 1f else 0f,
        if (LocalReduceMotion.current) snap() else tween(230, easing = GlassMotion.glide),
        label = "interruptor"
    )
    Box(
        Modifier.size(width = 56.dp, height = 48.dp)
            .semantics { contentDescription = label }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center
    ) {
        GlassSurface(Modifier.size(width = 51.dp, height = 29.dp), CircleShape, GlassTone.QUIET) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = progress.value }.background(
                Brush.linearGradient(listOf(DeepViolet, ElectricBlue))
            ))
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(21.dp)
                    .graphicsLayer { translationX = 22.dp.toPx() * progress.value }
                    .clip(CircleShape).background(if (checked || colors.dark) Color(0xFFF4EFFF) else colors.accent)
            )
        }
    }
}

private val Spanish = Locale.forLanguageTag("es")

private fun fileSize(bytes: Long): String {
    val giga = bytes >= 1_000_000_000
    val value = bytes.toDouble() / if (giga) 1_000_000_000 else 1_000_000
    val number = NumberFormat.getNumberInstance(Spanish).apply {
        maximumFractionDigits = if (giga) 2 else 0
    }
    return number.format(value) + if (giga) " GB" else " MB"
}
