package com.ialocal.android.ui

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ialocal.android.chat.ChatUiState
import com.ialocal.android.chat.ChatViewModel
import com.ialocal.android.chat.LiveAnswer
import com.ialocal.android.chat.Message
import com.ialocal.android.chat.MessageState
import com.ialocal.android.chat.Role
import kotlinx.coroutines.launch

@Composable
internal fun ChatScreen(
    state: ChatUiState,
    live: LiveAnswer?,
    modifier: Modifier,
    onModels: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val reduce = LocalReduceMotion.current
    Column(modifier.imePadding()) {
        val listState = rememberLazyListState()
        LaunchedEffect(state.selectedChat, state.messages.size) {
            if (state.messages.isNotEmpty()) listState.scrollToItem(0)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                state.loading && state.messages.isEmpty() -> {
                    CircularProgressIndicator(Modifier.size(26.dp).semantics { contentDescription = "Cargando historial" })
                }
                state.messages.isEmpty() -> Welcome(onSuggestion = { example ->
                    draft = example
                    requester.requestFocus()
                })
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(state.messages.asReversed(), key = { it.id }) { item ->
                        val body = live?.takeIf { it.id == item.id }?.body ?: item.body
                        MessageItem(
                            item, body,
                            if (reduce) Modifier else Modifier.animateItem(
                                fadeInSpec = tween(190), placementSpec = null, fadeOutSpec = null
                            )
                        )
                    }
                }
            }
        }
        Composer(
            state = state, draft = draft, onDraft = { draft = it }, requester = requester,
            onModels = onModels,
            onSend = { onSend(draft); draft = ""; focus.clearFocus() },
            onStop = onStop,
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()
        )
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    draft: String,
    onDraft: (String) -> Unit,
    requester: FocusRequester,
    onModels: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier
) {
    val colors = LocalGlass.current
    GlassSurface(modifier.padding(horizontal = 18.dp, vertical = 9.dp),
        shape = RoundedCornerShape(28.dp), depth = true) {
        Column(Modifier.fillMaxWidth().padding(7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!state.loading) {
                val hasModel = state.activeModel != null
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                        .clickable(role = SemanticsRole.Button, onClick = onModels)
                        .heightIn(min = 48.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(Icons.Default.Info, null, Modifier.size(15.dp), tint = colors.accent)
                    Text(
                        if (hasModel) "Modelo local" else "Configurar modelo",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.accent
                    )
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(15.dp), tint = colors.softInk)
                }
                HorizontalDivider(Modifier.padding(horizontal = 9.dp), color = colors.divider)
            }
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { if (it.length <= ChatViewModel.MAX_MESSAGE_CHARS) onDraft(it) },
                    modifier = Modifier.weight(1f).focusRequester(requester)
                        .semantics { contentDescription = "Mensaje" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 5,
                    enabled = !state.generating && !state.loading,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 11.dp, vertical = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (draft.isEmpty()) Text(
                                "Escribe tu mensaje", color = colors.softInk, style = MaterialTheme.typography.bodyMedium
                            )
                            inner()
                        }
                    }
                )
                if (state.generating) {
                    GlassIconButton(
                        if (state.stopping) "Deteniendo" else "Detener",
                        onStop, enabled = !state.stopping, primary = true
                    ) {
                        Box(Modifier.size(13.dp).background(LocalContentColor.current, RoundedCornerShape(3.dp)))
                    }
                } else {
                    GlassIconButton(
                        "Enviar", onSend, primary = true,
                        enabled = state.activeModel != null && !state.loading &&
                            state.transfer == null && draft.isNotBlank()
                    ) { Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(20.dp)) }
                }
            }
        }
    }
}

private data class Suggestion(val title: String, val hint: String, val prompt: String, val icon: ImageVector)

private val suggestions = listOf(
    Suggestion("Código", "Entiende un fragmento",
        "Explícame la diferencia entre una función suspend y un hilo en Kotlin.", Icons.Default.Build),
    Suggestion("Matemáticas", "Resuelve un ejercicio",
        "Resuelve 3(x − 2) = 12 paso a paso.", Icons.Default.Add),
    Suggestion("Ideas", "Explora posibilidades",
        "Dame tres ideas sencillas para organizar una semana de estudio.", Icons.Default.Star),
    Suggestion("Redacción", "Encuentra las palabras",
        "Ayúdame a redactar un correo breve y amable para pedir una reunión.", Icons.Default.Edit)
)

@Composable
private fun Welcome(onSuggestion: (String) -> Unit) {
    val reduce = LocalReduceMotion.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val colors = LocalGlass.current
    LazyColumn(
        modifier = Modifier.widthIn(max = 572.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        item {
            AnimatedVisibility(
                visible = entered || reduce,
                enter = fadeIn(tween(300)) + slideInVertically(tween(330)) { it / 14 }
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    RibbonHero(Modifier.size(width = 224.dp, height = 169.dp))
                    Text("Piensa en local.", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                    Text(
                        "Ideas, código y preguntas en tu dispositivo.",
                        Modifier.padding(top = 7.dp, start = 10.dp, end = 10.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.softInk, textAlign = TextAlign.Center
                    )
                }
            }
        }
        item {
            Text("Para empezar", Modifier.padding(start = 3.dp, bottom = 10.dp),
                style = MaterialTheme.typography.titleSmall, color = colors.ink)
        }
        items(suggestions.chunked(2)) { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                for (suggestion in row) {
                    GlassSurface(
                        Modifier.weight(1f), shape = RoundedCornerShape(21.dp), tone = GlassTone.STANDARD
                    ) {
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable(role = SemanticsRole.Button) { onSuggestion(suggestion.prompt) }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                Modifier.size(31.dp).background(
                                    if (colors.dark) colors.accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.78f),
                                    RoundedCornerShape(10.dp)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(suggestion.icon, null, Modifier.size(17.dp), tint = colors.accent)
                            }
                            Text(suggestion.title, style = MaterialTheme.typography.titleSmall)
                            Text(suggestion.hint, style = MaterialTheme.typography.bodySmall, color = colors.softInk)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun MessageItem(message: Message, body: String, modifier: Modifier) {
    val colors = LocalGlass.current
    if (message.role == Role.USER) {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            GlassSurface(
                Modifier.widthIn(max = 460.dp),
                shape = RoundedCornerShape(22.dp, 22.dp, 7.dp, 22.dp), tone = GlassTone.TINTED
            ) {
                SelectionContainer {
                    Text(body, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        return
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RibbonMark(size = 26.dp)
            Text("IA Local", style = MaterialTheme.typography.labelLarge, color = colors.accent)
        }
        if (body.isNotBlank()) {
            GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(7.dp, 23.dp, 23.dp, 23.dp),
                tone = GlassTone.QUIET) {
                SelectionContainer {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val blocks = remember(body) { messageBlocks(body) }
                        for (block in blocks) when (block) {
                            is MessageBlock.Text -> Text(block.value, style = MaterialTheme.typography.bodyLarge)
                            is MessageBlock.Code -> CodeCard(block)
                        }
                    }
                }
            }
            if (message.state != MessageState.GENERATING) CopyButton("Copiar respuesta", body)
        }
        val status = when (message.state) {
            MessageState.COMPLETE -> null
            MessageState.GENERATING -> if (body.isBlank()) "Preparando respuesta…" else "Generando…"
            MessageState.STOPPED -> "Respuesta detenida"
            MessageState.INTERRUPTED -> "Respuesta interrumpida"
            MessageState.LIMITED -> "Límite de salida alcanzado"
            MessageState.ERROR -> "No se pudo completar la respuesta"
        }
        status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.softInk) }
    }
}

@Composable
private fun CodeCard(block: MessageBlock.Code) {
    val colors = LocalGlass.current
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), tone = GlassTone.SHEET) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(block.language.ifBlank { "Código" }, style = MaterialTheme.typography.labelLarge, color = colors.accent)
                CopyButton("Copiar código", block.value)
            }
            HorizontalDivider(color = colors.divider)
            Text(
                block.value, Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = colors.ink
            )
        }
    }
}

@Composable
private fun CopyButton(label: String, text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
    }) { Text(label, style = MaterialTheme.typography.labelMedium) }
}
