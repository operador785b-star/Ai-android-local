package com.ialocal.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ialocal.android.chat.Chat
import com.ialocal.android.chat.ChatUiState
import com.ialocal.android.chat.ChatViewModel
import kotlinx.coroutines.launch

private enum class Screen { CHAT, MODELS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalApp(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    var modelParent by rememberSaveable { mutableStateOf(Screen.CHAT) }
    val back = { screen = if (screen == Screen.MODELS) modelParent else Screen.CHAT }
    if (screen != Screen.CHAT) BackHandler(onBack = back)
    val stateHolder = rememberSaveableStateHolder()

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        val message = state.notice ?: return@LaunchedEffect
        snackbars.showSnackbar(message)
        vm.dismissNotice(message)
    }

    var importName by rememberSaveable { mutableStateOf("") }
    var importHash by rememberSaveable { mutableStateOf("") }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri, importName, importHash)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = screen == Screen.CHAT || drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerState = drawer) {
                ChatDrawer(
                    state = state,
                    onSelect = { id ->
                        vm.selectChat(id)
                        screen = Screen.CHAT
                        scope.launch { drawer.close() }
                    },
                    onRename = vm::renameChat,
                    onDelete = vm::deleteChat
                )
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbars) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            val chat = state.chats.firstOrNull { it.id == state.selectedChat }
                            Text(
                                when (screen) {
                                    Screen.CHAT -> chat?.title ?: "IA Local"
                                    Screen.MODELS -> "Modelos"
                                    Screen.SETTINGS -> "Ajustes"
                                },
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (screen == Screen.CHAT) {
                                Text(
                                    state.activeModel?.name ?: "Asistente en tu dispositivo",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (screen == Screen.CHAT) {
                            IconButton(onClick = { scope.launch { drawer.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Historial")
                            }
                        } else {
                            IconButton(onClick = back) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (screen == Screen.MODELS && modelParent == Screen.SETTINGS)
                                        "Volver a ajustes" else "Volver al chat")
                            }
                        }
                    },
                    actions = {
                        if (screen == Screen.CHAT) {
                            IconButton(
                                enabled = !state.loading && !state.generating && state.selectedChat != null,
                                onClick = { vm.selectChat(null) }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Nuevo chat")
                            }
                            IconButton(onClick = { screen = Screen.SETTINGS }) {
                                Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                            }
                        }
                    }
                )
            }
        ) { inset ->
            val content = Modifier.fillMaxSize().padding(inset)
            when (screen) {
                Screen.CHAT -> stateHolder.SaveableStateProvider(Screen.CHAT.name) {
                    val live by vm.live.collectAsStateWithLifecycle()
                    ChatScreen(
                        state, live, content,
                        onModels = { modelParent = screen; screen = Screen.MODELS },
                        onSend = vm::send,
                        onStop = vm::stopGeneration
                    )
                }
                Screen.MODELS -> ModelScreen(
                    state, content,
                    onDownload = vm::downloadModel,
                    onImport = { name, hash ->
                        importName = name
                        importHash = hash
                        importFile.launch(arrayOf("*/*"))
                    },
                    onActivate = vm::activateModel,
                    onDelete = vm::deleteModel,
                    onCancel = vm::cancelTransfer
                )
                Screen.SETTINGS -> SettingsScreen(
                    state, content,
                    onConfigure = vm::configure,
                    onModels = { modelParent = screen; screen = Screen.MODELS }
                )
            }
        }
    }
}

@Composable
private fun ChatDrawer(
    state: ChatUiState,
    onSelect: (Long?) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    var rename by remember { mutableStateOf<Chat?>(null) }
    var delete by remember { mutableStateOf<Chat?>(null) }
    val canEdit = !state.loading && !state.generating
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Text("Historial", Modifier.padding(horizontal = 10.dp, vertical = 18.dp),
            style = MaterialTheme.typography.headlineSmall)
        FilledTonalButton(onClick = { onSelect(null) }, enabled = canEdit, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null, Modifier.padding(end = 8.dp))
            Text("Nuevo chat")
        }
        HorizontalDivider(Modifier.padding(vertical = 14.dp))
        if (state.chats.isEmpty()) {
            Text("Tus conversaciones guardadas aparecerán aquí.",
                Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.chats, key = { it.id }) { chat ->
                var expanded by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavigationDrawerItem(
                        label = { Text(chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        selected = state.selectedChat == chat.id,
                        onClick = { if (canEdit) onSelect(chat.id) },
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(onClick = { expanded = true }, enabled = canEdit) {
                            Icon(Icons.Default.MoreVert, "Opciones de ${chat.title}")
                        }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Cambiar nombre") },
                                onClick = { expanded = false; rename = chat }
                            )
                            DropdownMenuItem(
                                text = { Text("Eliminar chat") },
                                onClick = { expanded = false; delete = chat }
                            )
                        }
                    }
                }
            }
        }
    }
    rename?.let { chat ->
        var title by rememberSaveable(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Cambiar nombre") },
            text = {
                OutlinedTextField(title, { if (it.length <= 72) title = it },
                    label = { Text("Nombre del chat") }, singleLine = true)
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancelar") } },
            confirmButton = {
                TextButton(onClick = { onRename(chat.id, title); rename = null }, enabled = title.isNotBlank()) {
                    Text("Guardar")
                }
            }
        )
    }
    delete?.let { chat ->
        ConfirmDelete("Eliminar chat", "Se borrarán «${chat.title}» y sus mensajes.",
            dismiss = { delete = null }, confirm = { onDelete(chat.id); delete = null })
    }
}

@Composable
internal fun ConfirmDelete(title: String, description: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(description) },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
        confirmButton = {
            TextButton(onClick = confirm) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
        }
    )
}
