package dev.dbdelve.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import uniffi.dbdelve_core.NewValue
import dev.dbdelve.android.EditReview
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.InputChip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.dbdelve.android.QueryInput
import dev.dbdelve.android.AppViewModel
import dev.dbdelve.android.Completion
import dev.dbdelve.android.Pane
import dev.dbdelve.android.PendingGate
import dev.dbdelve.android.QueryTab
import uniffi.dbdelve_core.CatalogEntry
import uniffi.dbdelve_core.Destructive
import uniffi.dbdelve_core.Mode
import uniffi.dbdelve_core.ObjectKind
import uniffi.dbdelve_core.ResultSet
import uniffi.dbdelve_core.Stop
import uniffi.dbdelve_core.statementAt

/** Asks the device's own lock (fingerprint, face or PIN), then runs [onSuccess]. */
typealias Authenticate = (title: String, onSuccess: () -> Unit) -> Unit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(model: AppViewModel, authenticate: Authenticate) {
    val profile = model.profile ?: return
    val palette = LocalPalette.current
    BackHandler {
        if (model.pane != Pane.QUERY) model.pane = Pane.QUERY else model.disconnect()
    }
    // The four screens are pages a thumb swipes between, and the bottom bar
    // follows: tapping it and swiping are one navigation, not two.
    val pager = rememberPagerState(initialPage = model.pane.ordinal) { Pane.entries.size }
    LaunchedEffect(model.pane) {
        if (pager.targetPage != model.pane.ordinal) pager.animateScrollToPage(model.pane.ordinal)
    }
    LaunchedEffect(pager.settledPage) { model.pane = Pane.entries[pager.settledPage] }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { model.disconnect() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Disconnect")
                        }
                    },
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painterResource(profile.engine.logo()),
                                    contentDescription = profile.engine.label(),
                                    tint = palette.of(profile.engine),
                                    modifier = Modifier.padding(end = 6.dp).size(16.dp),
                                )
                                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                            }
                            Text(
                                listOf(profile.host, profile.database).filter { it.isNotBlank() }.joinToString(" / "),
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = { ModeChip(model, authenticate) },
                )
                // The connection's mode as a band, as the desktop colours its
                // title bar: hard to miss on the one screen that matters.
                Box(Modifier.fillMaxWidth().height(3.dp).background(palette.of(model.mode)))
            }
        },
        bottomBar = {
            if (!WindowInsets.isImeVisible) {
                NavigationBar {
                    Pane.entries.forEach { pane ->
                        NavigationBarItem(
                            selected = model.pane == pane,
                            onClick = { model.pane = pane },
                            icon = {
                                val rows = model.tab?.result?.rows?.size
                                when {
                                    pane == Pane.RESULTS && model.running ->
                                        BadgedBox(badge = { Badge(containerColor = palette.orange) }) { Icon(pane.icon(), null) }
                                    pane == Pane.RESULTS && model.tab?.error != null ->
                                        BadgedBox(badge = { Badge(containerColor = palette.red) { Text("!") } }) { Icon(pane.icon(), null) }
                                    pane == Pane.RESULTS && rows != null ->
                                        BadgedBox(
                                            badge = {
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                ) { Text(if (rows > 999) "999+" else "$rows") }
                                            },
                                        ) { Icon(pane.icon(), null) }
                                    else -> Icon(pane.icon(), null)
                                }
                            },
                            label = { Text(pane.label()) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            // While typing, a sideways drag is the user moving through text.
            userScrollEnabled = !WindowInsets.isImeVisible,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { page ->
            when (Pane.entries[page]) {
                Pane.QUERY -> QueryPane(model)
                Pane.RESULTS -> ResultsPane(model)
                Pane.SCHEMA -> SchemaPane(model)
                Pane.USERS -> UsersPane(model)
            }
        }
    }
    model.gate?.let { GateDialog(model, it, authenticate) }
}

@Composable
private fun ModeChip(model: AppViewModel, authenticate: Authenticate) {
    val color = LocalPalette.current.of(model.mode)
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 8.dp)) {
        Row(
            Modifier
                .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(7.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (model.mode == Mode.READ_ONLY) Icon(Icons.Default.Lock, null, tint = color, modifier = Modifier.size(13.dp))
            Text(model.mode.label(), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Mode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label()) },
                    onClick = {
                        open = false
                        // Raising a connection to Full asks the device first:
                        // on a phone the risk is a stray tap, not a typo.
                        if (mode == Mode.FULL && model.mode != Mode.FULL) {
                            authenticate("Switch ${model.profile?.name} to Full") { model.setMode(mode) }
                        } else {
                            model.setMode(mode)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun QueryPane(model: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow {
                QueryInput.entries.forEachIndexed { at, input ->
                    SegmentedButton(
                        selected = model.input == input,
                        onClick = { model.input = input },
                        shape = SegmentedButtonDefaults.itemShape(at, QueryInput.entries.size),
                        icon = {},
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(input.icon(), null, Modifier.size(18.dp))
                            Text(input.label(), Modifier.padding(start = 6.dp), fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (model.input == QueryInput.TEXT) TabCount(model)
        }
        when (model.input) {
            QueryInput.TEXT -> Editor(model)
            QueryInput.BUILDER -> QueryBuilder(model)
        }
    }
}

/** The open tabs as a count, the way a phone's browser shows them; tap for every tab at once. */
@Composable
private fun TabCount(model: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "${model.tabs.size} open tabs" }) {
        Box(
            Modifier.size(24.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("${model.tabs.size.coerceAtMost(99)}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (open) TabsSheet(model) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(model: AppViewModel, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${model.tabs.size} tabs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { model.newTab(); onDismiss() }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Text("New tab", Modifier.padding(start = 6.dp))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(max = 560.dp).navigationBarsPadding(),
        ) {
            itemsIndexed(model.tabs, key = { _, tab -> tab.id }) { at, tab ->
                val current = at == model.current
                Surface(
                    onClick = { model.select(at); onDismiss() },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    border = if (current) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Column(Modifier.padding(start = 10.dp, bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tab.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { model.closeTab(tab) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close ${tab.title}", modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            tab.text.text.ifBlank { "Empty" },
                            fontFamily = Mono,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = if (tab.text.text.isBlank()) palette.faint else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 10.dp).heightIn(min = 52.dp),
                        )
                        val result = tab.result
                        val (icon, note, tint) = when {
                            tab.error != null -> Triple(Icons.Default.ErrorOutline, "Error", palette.red)
                            result != null -> Triple(Icons.Default.CheckCircleOutline, "%,d rows".format(result.rows.size), palette.green)
                            else -> Triple(Icons.Default.Schedule, "Not run", palette.faint)
                        }
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(14.dp), tint = tint)
                            Text(note, fontSize = 11.sp, color = tint, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Editor(model: AppViewModel) {
    val session = model.session ?: return
    val engine = session.engine()
    val palette = LocalPalette.current
    val tab = model.tab ?: return
    Column(Modifier.fillMaxSize().imePadding()) {
        val strip = rememberLazyListState()
        LaunchedEffect(model.current, model.tabs.size) {
            if (model.current in model.tabs.indices) strip.animateScrollToItem(model.current)
        }
        LazyRow(
            state = strip,
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(model.tabs, key = { _, each -> each.id }) { at, each ->
                val selected = at == model.current
                InputChip(
                    selected = selected,
                    onClick = { model.select(at) },
                    label = { Text(each.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)) },
                    leadingIcon = if (each.error != null || each.result != null) {
                        {
                            val failed = each.error != null
                            Icon(
                                if (failed) Icons.Default.ErrorOutline else Icons.Default.CheckCircleOutline,
                                contentDescription = if (failed) "Error" else "Ran",
                                modifier = Modifier.size(16.dp),
                                tint = if (failed) palette.red else palette.green,
                            )
                        }
                    } else {
                        null
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close ${each.title}",
                                modifier = Modifier.size(16.dp).clickable { model.closeTab(each) },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            item {
                IconButton(onClick = { model.newTab() }) { Icon(Icons.Default.Add, contentDescription = "New tab") }
            }
        }

        BasicTextField(
            value = tab.text,
            onValueChange = { model.edit(tab, it) },
            textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(palette.orange),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            visualTransformation = remember(palette) { Highlight(palette) },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            decorationBox = { field ->
                Box {
                    if (tab.text.text.isEmpty()) {
                        Text(
                            if (engine == uniffi.dbdelve_core.Engine.MONGO) "db.collection.find({})" else "SELECT …",
                            fontFamily = Mono,
                            fontSize = 14.sp,
                            color = palette.faint,
                        )
                    }
                    field()
                }
            },
        )

        // What Run will send: the statement under the cursor, as typed.
        val cursor = tab.text.selection.start
        val span = remember(tab.text.text, cursor) { statementAt(engine, tab.text.text, cursor.toUInt()) }
        val next = span?.let { tab.text.text.substring(it.start.toInt(), it.end.toInt()) }
        Text(
            next?.let { "Runs: " + it.lineSequence().first().take(60) + if (it.contains('\n') || it.length > 60) " …" else "" }
                ?: "Nothing to run",
            fontFamily = Mono,
            fontSize = 11.sp,
            color = palette.faint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )

        val haptics = LocalHapticFeedback.current
        var savedOpen by remember { mutableStateOf(false) }
        var historyOpen by remember { mutableStateOf(false) }
        var bookmarksOpen by remember { mutableStateOf(false) }
        var explainOpen by remember { mutableStateOf(false) }
        var explainError by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowLimit(model)
            IconButton(onClick = { historyOpen = true }) { Icon(Icons.Default.History, contentDescription = "History") }
            Box {
                IconButton(onClick = { explainOpen = true }, enabled = next != null && !model.running) {
                    Icon(Icons.Default.AccountTree, contentDescription = "Explain")
                }
                DropdownMenu(expanded = explainOpen, onDismissRequest = { explainOpen = false }) {
                    val mongo = engine == uniffi.dbdelve_core.Engine.MONGO
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Plan")
                                Text("How it would run. Runs nothing.", fontSize = 12.sp, color = palette.faint)
                            }
                        },
                        onClick = {
                            explainOpen = false
                            explainError = model.explain(analyze = false)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(if (mongo) "Plan with execution stats" else "Plan with timings (ANALYZE)")
                                Text("Runs it to measure; a write still asks first.", fontSize = 12.sp, color = palette.faint)
                            }
                        },
                        onClick = {
                            explainOpen = false
                            explainError = model.explain(analyze = true)
                        },
                    )
                }
            }
            Box {
                IconButton(onClick = { bookmarksOpen = true }) { Icon(Icons.Default.Bookmarks, contentDescription = "Saved queries") }
                DropdownMenu(expanded = bookmarksOpen, onDismissRequest = { bookmarksOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Saved queries") },
                        leadingIcon = { Icon(Icons.Default.Bookmarks, null) },
                        onClick = {
                            bookmarksOpen = false
                            savedOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Save this query") },
                        leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) },
                        enabled = tab.text.text.isNotBlank(),
                        onClick = {
                            bookmarksOpen = false
                            saving = true
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (model.running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                FilledTonalButton(onClick = { model.cancel() }) {
                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                    Text("Stop", Modifier.padding(start = 6.dp))
                }
            } else {
                // Wide and at the thumb's corner: the one control used most.
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        model.runAtCursor()
                    },
                    enabled = next != null,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Run", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        if (savedOpen) SavedQueriesSheet(model) { savedOpen = false }
        if (historyOpen) HistorySheet(model) { historyOpen = false }
        explainError?.let { reason ->
            AlertDialog(
                onDismissRequest = { explainError = null },
                title = { Text("Can't explain this") },
                text = { Text(reason) },
                confirmButton = { TextButton(onClick = { explainError = null }) { Text("OK") } },
            )
        }
        if (saving) {
            SaveQueryDialog(
                initial = tab.title.takeUnless { it.startsWith("Query ") }.orEmpty(),
                onDismiss = { saving = false },
            ) { name ->
                model.saveQuery(name, tab.text.text)
                saving = false
            }
        }
        Completions(model, tab)
        AccessoryKeys(tab) { model.edit(tab, it) }
    }
}

@Composable
private fun RowLimit(model: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        // The cap on rows kept from a read: the statement itself is sent as typed.
        AssistChip(
            onClick = { open = true },
            label = { Text("%,d".format(model.rowLimit)) },
            leadingIcon = { Icon(Icons.Default.VerticalAlignBottom, contentDescription = "Row limit", modifier = Modifier.size(16.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(100, 1_000, 10_000).forEach { limit ->
                DropdownMenuItem(text = { Text("%,d rows".format(limit)) }, onClick = { model.rowLimit = limit; open = false })
            }
        }
    }
}

/** The keys a soft keyboard buries two layers deep. */
/**
 * Table and column names for the word at the cursor, above the key row; a tap
 * replaces the word with the name, quoted if the engine needs it.
 */
@Composable
private fun Completions(model: AppViewModel, tab: QueryTab) {
    val text = tab.text.text
    val cursor = tab.text.selection.start
    // Read the columns of tables as the text names them, once it settles.
    LaunchedEffect(text, model.catalog) {
        delay(300)
        model.loadColumnsFor(text)
    }
    if (!tab.text.selection.collapsed) return
    val found = remember(text, cursor, model.catalog, model.columnCache.toMap()) { model.completions(text, cursor) } ?: return
    val (word, suggestions) = found
    if (suggestions.isEmpty()) return
    val palette = LocalPalette.current
    LazyRow(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(suggestions, key = { it.insert }) { suggestion ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.clickable {
                    val next = text.substring(0, word.start) + suggestion.insert + text.substring(cursor)
                    val at = word.start + suggestion.insert.length
                    model.edit(tab, TextFieldValue(next, TextRange(at)))
                },
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (suggestion.kind == Completion.Kind.TABLE) Icons.Default.TableChart else Icons.Default.ViewColumn,
                        contentDescription = if (suggestion.kind == Completion.Kind.TABLE) "Table" else "Column",
                        tint = if (suggestion.kind == Completion.Kind.TABLE) palette.blue else palette.aqua,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(suggestion.name, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AccessoryKeys(tab: QueryTab, onPress: (TextFieldValue) -> Unit) {
    val keys = listOf("⇥", "(", ")", "'", "\"", ";", "*", "=", ".", ",", "{", "}", "[", "]", ":", "$", "_", "←", "→")
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { key ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.clickable { onPress(press(tab.text, key)) },
            ) {
                Text(
                    key,
                    fontFamily = Mono,
                    fontSize = 14.sp,
                    color = if (key in listOf("⇥", "←", "→")) LocalPalette.current.orange else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun press(value: TextFieldValue, key: String): TextFieldValue {
    val selection = value.selection
    return when (key) {
        "←" -> value.copy(selection = TextRange((selection.min - 1).coerceAtLeast(0)))
        "→" -> value.copy(selection = TextRange((selection.max + 1).coerceAtMost(value.text.length)))
        else -> {
            val insert = if (key == "⇥") "  " else key
            val text = value.text.replaceRange(selection.min, selection.max, insert)
            TextFieldValue(text, TextRange(selection.min + insert.length))
        }
    }
}

private val KEYWORDS = setOf(
    "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS", "NULL", "AS", "ON", "JOIN", "LEFT", "RIGHT",
    "INNER", "OUTER", "FULL", "CROSS", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL",
    "DISTINCT", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP", "TABLE",
    "VIEW", "INDEX", "TRUNCATE", "WITH", "CASE", "WHEN", "THEN", "ELSE", "END", "ASC", "DESC", "LIKE",
    "BETWEEN", "EXISTS", "EXPLAIN", "ANALYZE", "BEGIN", "COMMIT", "ROLLBACK", "RETURNING", "TRUE", "FALSE",
    "PRIMARY", "KEY", "DEFAULT", "SHOW", "USE", "GRANT", "REVOKE",
)

private val TOKEN = Regex(
    """(--[^\n]*|/\*[\s\S]*?(\*/|$))|('(?:[^'\\]|\\.|'')*'?|"(?:[^"\\]|\\.)*"?)|(\$?[A-Za-z_][A-Za-z0-9_]*)|(\b\d+(?:\.\d+)?\b)""",
)

/** Colours the buffer without changing a character of it. */
private class Highlight(private val palette: Palette) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text.text)
            TOKEN.findAll(text.text).forEach { match ->
                val range = match.range
                val style = when {
                    match.groups[1] != null -> SpanStyle(color = palette.faint, fontStyle = FontStyle.Italic)
                    match.groups[3] != null -> SpanStyle(color = palette.green)
                    match.groups[4] != null -> {
                        val word = match.value
                        when {
                            word.startsWith("$") -> SpanStyle(color = palette.aqua)
                            word.uppercase() in KEYWORDS -> SpanStyle(color = palette.red)
                            else -> null
                        }
                    }
                    match.groups[5] != null -> SpanStyle(color = palette.purple)
                    else -> null
                }
                style?.let { addStyle(it, range.first, range.last + 1) }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

@Composable
private fun ResultsPane(model: AppViewModel) {
    val tab = model.tab
    val palette = LocalPalette.current
    val error = tab?.error
    val result = tab?.result
    when {
        tab == null || (error == null && result == null) -> Centered("Run a statement to see its rows here.", Icons.Default.TableRows)
        error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("The server returned an error", color = palette.red, fontWeight = FontWeight.SemiBold)
            Text(error, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
            tab?.ran?.let { Statement(it) }
        }
        result != null -> Rows(model, tab!!, result, plan = tab.ran?.let(::isTextPlan) == true && result.columns.size == 1)
    }
    model.editReview?.let { review -> EditReviewDialog(model, review) }
}

/** What an edited cell shows: its new value, as the grid writes values. */
private fun NewValue.shown(): String? = when (this) {
    is NewValue.Text -> value
    is NewValue.Json -> value
    is NewValue.Null -> null
}

/**
 * The statements that write the edits back, exactly as they will run, before
 * anything is sent. Run passes them through the gate, which asks for
 * Read-write as it would for any typed UPDATE.
 */
@Composable
private fun EditReviewDialog(model: AppViewModel, review: EditReview) {
    val target = review.target
    AlertDialog(
        onDismissRequest = model::dismissEditReview,
        title = { Text("Write ${review.rows} row${if (review.rows == 1) "" else "s"} back?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Each row of ${target.table} is named by its primary key (${target.key.joinToString(", ")}), " +
                        "so each statement changes that one row at most. " +
                        if (review.prepared.size > 1) {
                            "They run in order; one that fails stops the rest."
                        } else if (review.rows > 1) {
                            "The server applies them together or not at all."
                        } else {
                            ""
                        } + " Needs Read-write.",
                    fontSize = 13.sp,
                )
                review.prepared.forEach { Statement(it.shown()) }
            }
        },
        confirmButton = { TextButton(onClick = model::applyEdits) { Text("Run") } },
        dismissButton = { TextButton(onClick = model::dismissEditReview) { Text("Cancel") } },
    )
}

/** A value for one cell: text, NULL, or on MongoDB a JSON value. */
@Composable
fun EditValueDialog(
    column: String,
    typeTag: String,
    current: String?,
    mongo: Boolean,
    onDismiss: () -> Unit,
    onSave: (NewValue) -> Unit,
) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    var isNull by remember { mutableStateOf(current == null) }
    // On MongoDB a value is typed: anything but a string goes in as JSON.
    var json by remember { mutableStateOf(mongo && typeTag.isNotEmpty() && typeTag != "string") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(column, fontFamily = Mono) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (typeTag.isNotEmpty()) Text(typeTag, fontFamily = Mono, fontSize = 12.sp, color = LocalPalette.current.faint)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; isNull = false },
                    enabled = !isNull,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isNull, onCheckedChange = { isNull = it })
                    Text("NULL")
                    if (mongo) {
                        Spacer(Modifier.weight(1f))
                        Checkbox(checked = json, onCheckedChange = { json = it }, enabled = !isNull)
                        Text("JSON value")
                    }
                }
                Text(
                    when {
                        isNull -> "Sets it to NULL."
                        mongo && json -> "Written as this JSON value: 12, true, {\"\$date\": \"2026-01-01T00:00:00Z\"}."
                        mongo -> "Written as a string."
                        else -> "Written as text; the server converts it to the column's type, as it would a typed value."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    when {
                        isNull -> NewValue.Null
                        mongo && json -> NewValue.Json(text)
                        else -> NewValue.Text(text)
                    },
                )
            }) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A plan the server returns as lines of text (Postgres, MySQL's ANALYZE), best read as text. */
private fun isTextPlan(ran: String): Boolean = ran.trimStart().startsWith("EXPLAIN", ignoreCase = true)

/** A text plan: one line per row, unwrapped, scrolled sideways rather than cut. */
@Composable
private fun PlanText(result: ResultSet) {
    Text(
        result.rows.joinToString("\n") { it.firstOrNull().orEmpty() },
        fontFamily = Mono,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        softWrap = false,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    )
}

@Composable
private fun Rows(model: AppViewModel, tab: QueryTab, result: ResultSet, plan: Boolean = false) {
    val title = tab.title
    val staged = { row: Int, column: Int -> tab.edits[row]?.get(result.columns[column].name) }
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    var records by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var opened by remember(result) { mutableStateOf<Int?>(null) }
    var copyNote by remember(result) { mutableStateOf<String?>(null) }
    // The clipboard crosses a binder transaction capped near 1 MB; past it
    // Android throws on the main thread, which is a crash, not an error.
    val copy = { text: String ->
        copyNote = when {
            text.length > CLIPBOARD_LIMIT -> "Too large to copy: lower the row limit and run it again."
            runCatching { clipboard.setText(AnnotatedString(text)) }.isFailure -> "The clipboard refused it."
            else -> null
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(selected = !records, onClick = { records = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GridOn, null, Modifier.size(18.dp))
                        Text("Grid", Modifier.padding(start = 6.dp), fontSize = 13.sp)
                    }
                }
                SegmentedButton(selected = records, onClick = { records = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ViewAgenda, null, Modifier.size(18.dp))
                        Text("Cards", Modifier.padding(start = 6.dp), fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { runCatching { shareCsv(context, title, csv(result)) } }) {
                Icon(Icons.Default.Share, contentDescription = "Share as CSV")
            }
            IconButton(onClick = { copy(csv(result)) }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy as CSV") }
        }
        copyNote?.let { Text(it, color = palette.red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
        Box(Modifier.weight(1f)) {
            when {
                result.columns.isEmpty() -> Centered(result.affected?.let { "$it rows affected" } ?: "Done")
                plan && !records -> PlanText(result)
                records -> Records(result, staged) { opened = it }
                else -> Grid(result, staged) { opened = it }
            }
        }
        model.editMessage?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, fontSize = 12.sp, modifier = Modifier.weight(1f), color = if (message.startsWith("Saved")) palette.green else palette.red)
                IconButton(onClick = { model.editMessage = null }) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
            }
        }
        if (tab.edits.isNotEmpty()) {
            var refusal by remember { mutableStateOf<String?>(null) }
            Row(
                Modifier.fillMaxWidth().background(palette.orange.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Edit, null, Modifier.size(18.dp), tint = palette.orange)
                Text(
                    "${tab.edits.size} row${if (tab.edits.size == 1) "" else "s"} changed",
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = { tab.edits.clear() }) { Text("Discard") }
                Button(onClick = { refusal = model.reviewEdits(tab) }, enabled = !model.running) { Text("Review") }
            }
            refusal?.let { reason ->
                AlertDialog(
                    onDismissRequest = { refusal = null },
                    title = { Text("Can't write these edits") },
                    text = { Text(reason) },
                    confirmButton = { TextButton(onClick = { refusal = null }) { Text("OK") } },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The limit bounded what was kept, never what was sent; say when it did.
            Text(
                "%,d rows".format(result.rows.size) + if (result.capped) " · stopped at limit" else "",
                fontSize = 12.sp,
                fontWeight = if (result.capped) FontWeight.SemiBold else FontWeight.Normal,
                color = if (result.capped) palette.yellow else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${result.elapsedMs} ms", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    var editing by remember(result) { mutableStateOf<Pair<Int, Int>?>(null) }
    opened?.let { index ->
        RowSheet(
            result,
            index,
            copy,
            editing = tab.source?.let {
                RowEditing(
                    key = tab.editTarget?.key,
                    notEditable = tab.notEditable,
                    staged = tab.edits[index].orEmpty(),
                    onEdit = { column -> editing = index to column },
                )
            },
        ) { opened = null }
    }
    editing?.let { (row, column) ->
        val name = result.columns[column].name
        val staged = tab.edits[row]?.get(name)
        EditValueDialog(
            column = name,
            typeTag = result.columns[column].typeTag,
            current = if (staged != null) staged.shown() else result.rows[row][column],
            mongo = model.session?.engine() == uniffi.dbdelve_core.Engine.MONGO,
            onDismiss = { editing = null },
        ) { value ->
            model.stageEdit(tab, row, name, value)
            editing = null
        }
    }
}

private const val CHAR_DP = 8.4f

/** Characters, well under the binder's ~1 MB once encoded as UTF-16. */
private const val CLIPBOARD_LIMIT = 200_000

@Composable
private fun Grid(result: ResultSet, staged: (Int, Int) -> NewValue?, open: (Int) -> Unit) {
    val palette = LocalPalette.current
    // Wide enough for what most of the column holds, not for its longest value.
    val widths = remember(result) {
        result.columns.mapIndexed { at, column ->
            val longest = result.rows.asSequence().take(200).maxOfOrNull { (it.getOrNull(at) ?: "NULL").length } ?: 0
            (maxOf(column.name.length + column.typeTag.length + 1, longest).coerceIn(4, 36) * CHAR_DP + 20).dp
        }
    }
    val numberWidth = ((result.rows.size.toString().length).coerceAtLeast(2) * CHAR_DP + 16).dp
    // One horizontal position for the header and every row; the row numbers
    // stay put beside them.
    val across: ScrollState = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize()) {
        stickyHeaderRow(result, widths, numberWidth, across)
        itemsIndexed(result.rows) { index, row ->
            Row(Modifier.height(34.dp)) {
                Cell("${index + 1}", numberWidth, palette.faint)
                Row(Modifier.horizontalScroll(across)) {
                    row.forEachIndexed { at, read ->
                        val change = staged(index, at)
                        val value = change?.shown() ?: read.takeIf { change == null }
                        val numeric = value?.toDoubleOrNull() != null
                        Box(
                            Modifier
                                .width(widths[at])
                                .height(34.dp)
                                .clickable { open(index) }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                value ?: "NULL",
                                fontFamily = Mono,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontStyle = if (value == null) FontStyle.Italic else FontStyle.Normal,
                                color = when {
                                    change != null -> palette.orange
                                    value == null -> palette.faint
                                    numeric -> palette.yellow
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                textDecoration = if (change != null) TextDecoration.Underline else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.stickyHeaderRow(
    result: ResultSet,
    widths: List<androidx.compose.ui.unit.Dp>,
    numberWidth: androidx.compose.ui.unit.Dp,
    across: ScrollState,
) {
    stickyHeader {
        val palette = LocalPalette.current
        Row(Modifier.height(36.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
            Cell("#", numberWidth, palette.faint)
            Row(Modifier.horizontalScroll(across)) {
                result.columns.forEachIndexed { at, column ->
                    Row(
                        Modifier.width(widths[at]).height(36.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(column.name, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (column.typeTag.isNotEmpty()) {
                            Text(" ${column.typeTag}", fontFamily = Mono, fontSize = 9.sp, color = palette.faint, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** The pinned row-number column. */
@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .width(width)
            .height(34.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(text, fontFamily = Mono, fontSize = 12.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun Records(result: ResultSet, staged: (Int, Int) -> NewValue?, open: (Int) -> Unit) {
    val palette = LocalPalette.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(result.rows) { index, row ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text("#${index + 1}", fontFamily = Mono, fontSize = 11.sp, color = palette.faint)
                    row.forEachIndexed { at, read ->
                        val change = staged(index, at)
                        val value = change?.shown() ?: read.takeIf { change == null }
                        Row(Modifier.fillMaxWidth().clickable { open(index) }.padding(vertical = 2.dp)) {
                            Text(result.columns[at].name, fontFamily = Mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                value ?: "NULL",
                                fontFamily = Mono,
                                fontSize = 12.sp,
                                fontStyle = if (value == null) FontStyle.Italic else FontStyle.Normal,
                                color = when {
                                    change != null -> palette.orange
                                    value == null -> palette.faint
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun csv(result: ResultSet): String {
    fun field(value: String?): String = when {
        value == null -> ""
        value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } -> "\"" + value.replace("\"", "\"\"") + "\""
        else -> value
    }
    return buildString {
        appendLine(result.columns.joinToString(",") { field(it.name) })
        result.rows.forEach { row -> appendLine(row.joinToString(",") { field(it) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemaPane(model: AppViewModel) {
    var filter by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Filter objects") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        model.catalogError?.let { Text(it, color = LocalPalette.current.red, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
        val shown = model.catalog.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
        PullToRefreshBox(isRefreshing = model.refreshingCatalog, onRefresh = { model.refreshCatalog() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                shown.groupBy { it.schema }.forEach { (schema, entries) ->
                    item(key = "schema" to schema) { SectionHeader(schema, entries.size) }
                    items(entries, key = { it.schema to it.name }) { entry -> CatalogRow(model, entry) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogRow(model: AppViewModel, entry: CatalogEntry) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(entry.kind.icon(), contentDescription = entry.kind.name.lowercase(), tint = palette.blue, modifier = Modifier.size(20.dp))
        Text(entry.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        entry.sizeBytes?.let { Text(size(it.toLong()), fontFamily = Mono, fontSize = 11.sp, color = palette.faint) }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            SheetTitle(entry.name, entry.schema)
            Column(Modifier.navigationBarsPadding()) {
                SheetAction("Preview rows", detail = "DBDelve's own query, with a LIMIT, in a tab of its own", icon = Icons.Default.PlayArrow) {
                    open = false
                    model.preview(entry)
                }
                SheetAction("Build a query", detail = "Pick columns, filters and a sort by tapping", icon = Icons.Default.Tune) {
                    open = false
                    model.build(entry)
                }
                SheetAction("Copy name", icon = Icons.Default.ContentCopy) {
                    open = false
                    runCatching { clipboard.setText(AnnotatedString(entry.name)) }
                }
            }
        }
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.1f KB".format(bytes / 1024.0)
}

@Composable
private fun GateDialog(model: AppViewModel, pending: PendingGate, authenticate: Authenticate) {
    val palette = LocalPalette.current
    val name = model.profile?.name ?: ""
    val stop = pending.stop
    AlertDialog(
        onDismissRequest = { model.dismissGate() },
        title = {
            Text(
                when (stop) {
                    is Stop.Upgrade -> "This statement needs ${stop.mode.label()}"
                    is Stop.Confirm -> "This statement ${destroys(stop.kind)}"
                    Stop.RunOnce -> "DBDelve can't read this statement"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when (stop) {
                        is Stop.Upgrade -> "$name is ${model.mode.label()}." +
                            if (pending.verdict.destructive.isNotEmpty()) " It ${pending.verdict.destructive.joinToString(" and ") { destroys(it) }}." else ""
                        is Stop.Confirm -> "Check the statement below before it runs on $name."
                        Stop.RunOnce ->
                            "It may be a typo, a backslash the server could read differently, or " +
                                "something this app can't classify. Nothing but you stops it writing."
                    },
                )
                Statement(pending.statement)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                when (stop) {
                    is Stop.Upgrade -> {
                        TextButton(onClick = { authenticate("Run once in ${stop.mode.label()} on $name") { model.runOnceIn(stop.mode) } }) {
                            Text("Run once in ${stop.mode.label()}")
                        }
                        TextButton(onClick = {
                            if (stop.mode == Mode.FULL) {
                                authenticate("Switch $name to Full") { model.switchAndRun(stop.mode) }
                            } else {
                                model.switchAndRun(stop.mode)
                            }
                        }) { Text("Switch $name to ${stop.mode.label()}") }
                    }
                    is Stop.Confirm -> Button(
                        onClick = { model.confirm(stop.kind) },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.red),
                    ) { Text("Run it") }
                    Stop.RunOnce -> TextButton(onClick = { model.runUnreadableOnce() }) { Text("Run once") }
                }
            }
        },
        dismissButton = { TextButton(onClick = { model.dismissGate() }) { Text("Cancel") } },
    )
}

private fun destroys(kind: Destructive): String = when (kind) {
    Destructive.DROP -> "drops an object and its data"
    Destructive.TRUNCATE -> "empties a table"
    Destructive.UNFILTERED_DELETE -> "deletes every row"
    Destructive.UNREADABLE -> "could not be read"
}

/** A statement verbatim: what is approved is the text, not a summary of it. */
@Composable
private fun Statement(text: String) {
    Text(
        text,
        fontFamily = Mono,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    )
}

@Composable
private fun Centered(text: String, icon: ImageVector? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let { Icon(it, null, Modifier.size(48.dp).padding(bottom = 8.dp), tint = LocalPalette.current.faint) }
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun ObjectKind.icon(): ImageVector = when (this) {
    ObjectKind.TABLE -> Icons.Default.TableChart
    ObjectKind.VIEW -> Icons.Default.Visibility
    ObjectKind.COLLECTION -> Icons.Default.DataObject
}

private fun Pane.icon(): ImageVector = when (this) {
    Pane.QUERY -> Icons.Default.Code
    Pane.RESULTS -> Icons.Default.TableRows
    Pane.SCHEMA -> Icons.Default.AccountTree
    Pane.USERS -> Icons.Default.Group
}

private fun Pane.label(): String = when (this) {
    Pane.QUERY -> "Query"
    Pane.RESULTS -> "Results"
    Pane.SCHEMA -> "Schema"
    Pane.USERS -> "Users"
}

private fun QueryInput.icon(): ImageVector = when (this) {
    QueryInput.TEXT -> Icons.Default.Code
    QueryInput.BUILDER -> Icons.Default.Tune
}

private fun QueryInput.label(): String = when (this) {
    QueryInput.TEXT -> "SQL"
    QueryInput.BUILDER -> "Builder"
}
