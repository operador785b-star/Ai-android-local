package com.ialocal.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ialocal.android.chat.Chat
import com.ialocal.android.chat.ChatUiState
import com.ialocal.android.chat.ChatViewModel
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Screen { CHAT, MODELS, SETTINGS }

@Composable
fun LocalApp(vm: ChatViewModel, onDarkTheme: (Boolean) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    LocalTheme(state.appearance) {
        val dark = LocalGlass.current.dark
        SideEffect { onDarkTheme(dark) }
        AppNavigation(vm, state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppNavigation(vm: ChatViewModel, state: ChatUiState) {
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    var modelParent by rememberSaveable { mutableStateOf(Screen.CHAT) }
    val back = { screen = if (screen == Screen.MODELS) modelParent else Screen.CHAT }
    val showModels = { modelParent = screen; screen = Screen.MODELS }
    val stateHolder = rememberSaveableStateHolder()
    val colors = LocalGlass.current
    val reduce = LocalReduceMotion.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val setDrawer: (DrawerValue) -> Unit = { value ->
        scope.launch {
            when {
                reduce -> drawer.snapTo(value)
                value == DrawerValue.Open -> drawer.open()
                else -> drawer.close()
            }
        }
    }
    BackHandler(enabled = drawer.isOpen && reduce) { setDrawer(DrawerValue.Closed) }
    BackHandler(enabled = screen != Screen.CHAT && !drawer.isOpen, onBack = back)

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
    val drawerShape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)

    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop()
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = !reduce && (screen == Screen.CHAT || drawer.isOpen),
            scrimColor = Color(0xFF06091A).copy(alpha = if (colors.dark) 0.62f else 0.34f),
            drawerContent = {
                val sheet = Modifier.fillMaxHeight().widthIn(max = 354.dp).fillMaxWidth(0.94f)
                val sheetInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)
                val drawerBody: @Composable ColumnScope.() -> Unit = {
                    GlassSurface(Modifier.fillMaxSize(), drawerShape, GlassTone.SHEET) {
                        ChatDrawer(
                            state = state,
                            onClose = { setDrawer(DrawerValue.Closed) },
                            onSelect = { id ->
                                vm.selectChat(id)
                                screen = Screen.CHAT
                                setDrawer(DrawerValue.Closed)
                            },
                            onRename = vm::renameChat,
                            onDelete = vm::deleteChat
                        )
                    }
                }
                if (reduce) ModalDrawerSheet(
                    modifier = sheet, drawerShape = drawerShape,
                    drawerContainerColor = Color.Transparent, drawerContentColor = colors.ink,
                    windowInsets = sheetInsets, content = drawerBody
                ) else ModalDrawerSheet(
                    drawerState = drawer, modifier = sheet, drawerShape = drawerShape,
                    drawerContainerColor = Color.Transparent, drawerContentColor = colors.ink,
                    windowInsets = sheetInsets, content = drawerBody
                )
            }
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = colors.ink,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbars) }
            ) { inset ->
                Column(Modifier.fillMaxSize().padding(inset).consumeWindowInsets(inset)) {
                    GlassToolbar(
                        screen, modelParent, state,
                        onHistory = { setDrawer(DrawerValue.Open) },
                        onSettings = { screen = Screen.SETTINGS },
                        onBack = back,
                        onModels = showModels
                    )
                    AnimatedContent(
                        targetState = screen,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            if (reduce) EnterTransition.None togetherWith ExitTransition.None
                            else (fadeIn(tween(220)) + slideInHorizontally(tween(245)) {
                                if (targetState == Screen.CHAT) -it / 20 else it / 20
                            }) togetherWith fadeOut(tween(135))
                        },
                        label = "navegación"
                    ) { page ->
                        val content = Modifier.fillMaxSize()
                        when (page) {
                            Screen.CHAT -> stateHolder.SaveableStateProvider(Screen.CHAT.name) {
                                val live by vm.live.collectAsStateWithLifecycle()
                                ChatScreen(state, live, content, showModels, vm::send, vm::stopGeneration)
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
                                state, content, vm::configure, vm::configureAppearance, showModels
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassToolbar(
    screen: Screen,
    modelParent: Screen,
    state: ChatUiState,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onModels: () -> Unit
) {
    val colors = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        if (screen == Screen.CHAT) {
            GlassIconButton("Historial", onHistory) { Icon(Icons.Default.Menu, null, Modifier.size(21.dp)) }
            Row(
                Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                PrismMark(size = 34.dp)
                Column(Modifier.weight(1f)) {
                    val chat = state.chats.firstOrNull { it.id == state.selectedChat }
                    Text(
                        chat?.title ?: "IA Local", style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        Modifier.clickable(onClickLabel = "Abrir modelos locales", onClick = onModels),
                        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(5.dp).background(
                            if (state.activeModel != null) BrightBlue else colors.softInk, CircleShape
                        ))
                        Text(
                            state.activeModel?.name ?: "Sin modelo activo",
                            style = MaterialTheme.typography.labelSmall, color = colors.softInk,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            GlassIconButton("Ajustes", onSettings) { Icon(Icons.Default.Settings, null, Modifier.size(20.dp)) }
        } else {
            val description = if (screen == Screen.MODELS && modelParent == Screen.SETTINGS) "Volver a ajustes"
                else "Volver al chat"
            GlassIconButton(description, onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(
                if (screen == Screen.SETTINGS) "Ajustes" else "Modelos locales",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)
            )
            PrismMark(size = 32.dp)
        }
    }
}

@Composable
private fun ChatDrawer(
    state: ChatUiState,
    onClose: () -> Unit,
    onSelect: (Long?) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    val colors = LocalGlass.current
    var query by rememberSaveable { mutableStateOf("") }
    var rename by remember { mutableStateOf<Chat?>(null) }
    var delete by remember { mutableStateOf<Chat?>(null) }
    val canEdit = !state.loading && !state.generating
    val matches = remember(state.chats, query) { searchChats(state.chats, query) }

    Column(
        Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            PrismMark(size = 31.dp)
            Text("Historial", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            GlassIconButton("Cerrar historial", onClose) { Icon(Icons.Default.Close, null, Modifier.size(19.dp)) }
        }
        GlassButton({ query = ""; onSelect(null) }, Modifier.fillMaxWidth(), enabled = canEdit) {
            Icon(Icons.Default.Add, null, Modifier.size(20.dp))
            Text("Nuevo chat")
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(96) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Buscar conversaciones" },
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text("Buscar conversaciones", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = colors.divider,
                unfocusedContainerColor = colors.glassBottom.copy(alpha = 0.32f)
            ),
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton({ query = "" }) { Icon(Icons.Default.Close, "Borrar búsqueda") } }
            } else null,
            singleLine = true
        )
        HorizontalDivider(color = colors.divider)
        if (matches.isEmpty()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (query.isBlank()) "Aún no hay chats" else "Sin coincidencias",
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    if (query.isBlank()) "Tus conversaciones aparecerán aquí al enviar el primer mensaje."
                    else "Prueba con otra palabra del título.",
                    color = colors.softInk, style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(matches, key = { it.id }) { chat ->
                    var expanded by remember { mutableStateOf(false) }
                    val selected = chat.id == state.selectedChat
                    GlassSurface(tone = if (selected) GlassTone.TINTED else GlassTone.QUIET,
                        shape = RoundedCornerShape(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(1f).heightIn(min = 56.dp)
                                    .semantics { this.selected = selected }
                                    .clickable(enabled = canEdit) { onSelect(chat.id) }
                                    .padding(start = 12.dp, top = 9.dp, bottom = 9.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(chat.title, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(chatDate(chat.updatedAt), style = MaterialTheme.typography.bodySmall, color = colors.softInk)
                            }
                            Box {
                                IconButton(onClick = { expanded = true }, enabled = canEdit) {
                                    Icon(Icons.Default.MoreVert, "Opciones de ${chat.title}", tint = colors.softInk)
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

private val diacritics = Regex("\\p{M}+")

internal fun searchChats(chats: List<Chat>, query: String): List<Chat> {
    fun normalize(text: String) = diacritics.replace(Normalizer.normalize(text.trim(), Normalizer.Form.NFD), "")
        .lowercase(Locale.ROOT)
    val search = normalize(query)
    return if (search.isBlank()) chats else chats.filter { normalize(it.title).contains(search) }
}

private val spanishDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es"))

private fun chatDate(unixMs: Long): String {
    val date = Instant.ofEpochMilli(unixMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return if (date == LocalDate.now()) "Hoy" else date.format(spanishDate)
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
