package com.ialocal.android.ui

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    Column(modifier.imePadding()) {
        val listState = rememberLazyListState()
        LaunchedEffect(state.selectedChat, state.messages.size) {
            if (state.messages.isNotEmpty()) listState.scrollToItem(0)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                state.loading && state.messages.isEmpty() -> {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = "Cargando historial" })
                }
                state.messages.isEmpty() -> Welcome(state, onModels) { draft = it }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(state.messages.asReversed(), key = { it.id }) { item ->
                        val text = live?.takeIf { it.id == item.id }?.body ?: item.body
                        MessageItem(item, text)
                    }
                }
            }
        }
        HorizontalDivider()
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (state.messages.isNotEmpty()) {
                    AssistChip(
                        label = { Text(if (state.activeModel == null) "Configurar modelo" else "Modelos locales") },
                        onClick = onModels
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= ChatViewModel.MAX_MESSAGE_CHARS) draft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Mensaje") },
                        maxLines = 5,
                        enabled = !state.generating && !state.loading
                    )
                    if (state.generating) {
                        FilledIconButton(
                            onClick = onStop, enabled = !state.stopping,
                            modifier = Modifier.size(48.dp).semantics {
                                contentDescription = if (state.stopping) "Deteniendo" else "Detener"
                            }
                        ) {
                            Box(Modifier.size(14.dp).clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onPrimary))
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                onSend(draft)
                                draft = ""
                                focus.clearFocus()
                            },
                            enabled = state.activeModel != null && !state.loading &&
                                state.transfer == null && draft.isNotBlank(),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Enviar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome(state: ChatUiState, onModels: () -> Unit, onSuggestion: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(Modifier.widthIn(max = 490.dp).fillMaxWidth()) {
                val accent = MaterialTheme.colorScheme.primary
                Box(
                    Modifier.size(58.dp).clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(34.dp)) {
                        val center = size.width / 2
                        val inset = size.width * 0.13f
                        val ray = size.width * 0.10f
                        val outline = Path().apply {
                            moveTo(center, inset)
                            lineTo(center + ray, center - ray)
                            lineTo(size.width - inset, center)
                            lineTo(center + ray, center + ray)
                            lineTo(center, size.height - inset)
                            lineTo(center - ray, center + ray)
                            lineTo(inset, center)
                            lineTo(center - ray, center - ray)
                            close()
                        }
                        drawPath(outline, accent)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("¿Qué quieres resolver?", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (state.activeModel == null) {
                    Text(
                        "Instala Qwen3.5-2B para empezar. Después podrás conversar sin conexión.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onModels) { Text("Configurar modelo") }
                } else {
                    Text("Tus conversaciones se quedan en el teléfono.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    val examples = listOf(
                        "Explicar código" to "Explícame la diferencia entre una función suspend y un hilo en Kotlin.",
                        "Resolver un ejercicio" to "Resuelve 3(x − 2) = 12 paso a paso.",
                        "Empezar a escribir" to "Ayúdame a redactar un correo breve y amable para pedir una reunión."
                    )
                    for ((label, question) in examples) {
                        FilledTonalButton(
                            onClick = { onSuggestion(question) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) { Text(label) }
                    }
                    TextButton(onClick = onModels) { Text("Modelos locales") }
                }
            }
        }
    }
}

@Composable
private fun MessageItem(message: Message, body: String) {
    if (message.role == Role.USER) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp),
                modifier = Modifier.fillMaxWidth(0.86f)
            ) {
                SelectionContainer {
                    Text(body, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("IA Local", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (body.isNotBlank()) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val blocks = remember(body) { messageBlocks(body) }
                    for (block in blocks) {
                        when (block) {
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
        status?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CodeCard(block: MessageBlock.Code) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(block.language.ifBlank { "Código" }, style = MaterialTheme.typography.labelLarge)
                CopyButton("Copiar código", block.value)
            }
            HorizontalDivider()
            Text(
                block.value, Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
                fontFamily = FontFamily.Monospace
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
    }) { Text(label) }
}
