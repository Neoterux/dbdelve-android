package dev.dbdelve.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dbdelve.android.AppViewModel
import dev.dbdelve.android.BuilderFilter
import dev.dbdelve.android.BuilderState
import dev.dbdelve.android.reason
import uniffi.dbdelve_core.Engine
import uniffi.dbdelve_core.FilterOp
import uniffi.dbdelve_core.fillParameters
import uniffi.dbdelve_core.Column as DbColumn

/**
 * A query made by tapping: a table, its columns, filters in AND groups
 * joined by OR, a count or grouping, a sort and a limit. No joins. It writes the statement on screen as it goes; Run and Edit as SQL
 * both put that statement in a tab of its own.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QueryBuilder(model: AppViewModel) {
    val engine = model.session?.engine() ?: return
    val state = model.builder
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.size(2.dp))
            TableButton(state) { picking = true }
            val table = state.table
            if (table == null) {
                Text(
                    "Choose a table to start. Each choice below writes part of the statement, shown at the bottom before anything runs.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            } else {
                state.columnsError?.let { Text(it, color = LocalPalette.current.red, fontFamily = Mono, fontSize = 12.sp) }
                Section(Icons.Default.ViewColumn, "Columns")
                if (state.aggregates) {
                    Text(
                        "Grouped or counted: the result's columns are the groups and the count.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FilterChip(selected = state.chosen.isEmpty(), onClick = { state.chosen.clear() }, label = { Text("All") })
                        state.columns.forEach { column ->
                            val on = column.name in state.chosen
                            FilterChip(
                                selected = on,
                                onClick = { if (on) state.chosen.remove(column.name) else state.chosen.add(column.name) },
                                label = { Text(column.name, fontFamily = Mono, fontSize = 12.sp) },
                            )
                        }
                    }
                }
                if (state.loadingColumns) Text("Reading columns…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Section(Icons.Default.FilterAlt, "Filters")
                state.groups.forEachIndexed { at, group ->
                    if (at > 0) OrDivider()
                    group.forEach { filter -> FilterCard(state, group, filter, engine) }
                    AssistChip(
                        onClick = { group.add(BuilderFilter(state.columns.firstOrNull()?.name.orEmpty())) },
                        label = { Text(if (group.isEmpty()) "Add filter" else "And…") },
                        leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                    )
                }
                if (state.groups.last().isNotEmpty()) {
                    AssistChip(
                        onClick = { state.groups.add(mutableStateListOf(BuilderFilter(state.columns.firstOrNull()?.name.orEmpty()))) },
                        label = { Text("Or…") },
                        leadingIcon = { Icon(Icons.Default.CallSplit, null, Modifier.size(18.dp)) },
                    )
                }

                Section(Icons.Default.Functions, "Summarize")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FilterChip(
                        selected = state.count,
                        onClick = { state.count = !state.count },
                        label = { Text("Count rows") },
                        leadingIcon = { Icon(Icons.Default.Numbers, null, Modifier.size(16.dp)) },
                    )
                }
                Text("Group by", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // MongoDB reads `a.b` and `$a` as paths, not names: those
                    // fields are not offered to group by.
                    state.columns.filter { engine != Engine.MONGO || ('.' !in it.name && !it.name.startsWith('$')) }.forEach { column ->
                        val on = column.name in state.groupBy
                        FilterChip(
                            selected = on,
                            onClick = { if (on) state.groupBy.remove(column.name) else state.groupBy.add(column.name) },
                            label = { Text(column.name, fontFamily = Mono, fontSize = 12.sp) },
                        )
                    }
                }

                Section(Icons.AutoMirrored.Filled.Sort, "Sort")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColumnMenu(state.sortable(), state.columns, state.orderBy?.takeIf { it in state.sortable() }, none = "Unsorted") { state.orderBy = it }
                    if (state.orderBy != null && state.orderBy in state.sortable()) {
                        FilledTonalIconButton(onClick = { state.descending = !state.descending }) {
                            Icon(
                                if (state.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                contentDescription = if (state.descending) "Descending" else "Ascending",
                            )
                        }
                    }
                }

                Section(Icons.Default.VerticalAlignBottom, "Limit")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null, 10, 100, 1_000).forEach { limit ->
                        FilterChip(
                            selected = state.limit == limit,
                            onClick = { state.limit = limit },
                            label = { Text(limit?.let { "%,d".format(it) } ?: "None") },
                        )
                    }
                }

                error?.let { Text(it, color = LocalPalette.current.red, fontSize = 12.sp) }
                Spacer(Modifier.size(8.dp))
            }
        }
        // What will run, pinned where it can't scroll away from the Run button.
        val built = state.built(engine)
        val filled = built?.let { runCatching { fillParameters(engine, it.text, state.bindings(it)) } }
        HorizontalDivider()
        if (filled != null) {
            Text(
                filled.fold({ it }, { it.reason() }),
                fontFamily = Mono,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = if (filled.isFailure) LocalPalette.current.red else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 132.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ready = state.table != null
            IconButton(onClick = { saving = true }, enabled = ready) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = "Save with parameters")
            }
            OutlinedButton(onClick = { error = model.openBuilt(run = false) }, enabled = ready) {
                Icon(Icons.Default.Code, null, Modifier.size(18.dp))
                Text("SQL", Modifier.padding(start = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { error = model.openBuilt(run = true) },
                enabled = ready && !model.running,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Text("Run", Modifier.padding(start = 6.dp))
            }
        }
    }
    if (picking) TablePicker(model, onDismiss = { picking = false })
    if (saving) {
        SaveQueryDialog(
            initial = state.table?.name.orEmpty(),
            onDismiss = { saving = false },
        ) { name ->
            model.saveBuilt(name)
            saving = false
        }
    }
}

@Composable
private fun TableButton(state: BuilderState, onClick: () -> Unit) {
    val table = state.table
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(table?.kind?.icon() ?: Icons.Default.Search, null, tint = LocalPalette.current.blue)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(table?.name ?: "Choose a table", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                table?.let { Text(it.schema, fontFamily = Mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(Icons.Default.ArrowDropDown, null)
        }
    }
}

@Composable
private fun Section(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.weight(1f))
        Text("OR", style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.blue, fontWeight = FontWeight.Bold)
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun FilterCard(state: BuilderState, group: SnapshotStateList<BuilderFilter>, filter: BuilderFilter, engine: Engine) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.columns.isEmpty()) {
                    // No columns to offer (an empty collection): type the field.
                    OutlinedTextField(
                        value = filter.column,
                        onValueChange = { filter.column = it },
                        placeholder = { Text("field") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Box(Modifier.weight(1f)) {
                        ColumnMenu(state.columns.map { it.name }, state.columns, filter.column, none = null) { filter.column = it.orEmpty() }
                    }
                }
                OpMenu(filter, engine)
                IconButton(onClick = { state.removeFilter(group, filter) }) { Icon(Icons.Default.Close, contentDescription = "Remove filter") }
            }
            filter.values.forEachIndexed { at, value ->
                val label = when (filter.op) {
                    FilterOp.BETWEEN -> if (at == 0) "from" else "to"
                    else -> null
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { filter.values[at] = it },
                    label = label?.let { { Text(it) } },
                    placeholder = { Text(if (filter.isPattern) likeHint(engine) else "value") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (filter.asNumber == true && !filter.isPattern) KeyboardType.Decimal else KeyboardType.Text,
                        autoCorrectEnabled = false,
                    ),
                    trailingIcon = {
                        Row {
                            if (filter.op == FilterOp.IN && filter.values.size > 1) {
                                IconButton(onClick = { filter.values.removeAt(at) }) { Icon(Icons.Default.Close, contentDescription = "Remove value") }
                            }
                            if (!filter.isPattern && at == 0) {
                                // Text or number decides the literal: '5' and 5 differ on MongoDB.
                                IconButton(onClick = { filter.asNumber = !filter.isNumber }) {
                                    Icon(
                                        if (filter.isNumber) Icons.Default.Numbers else Icons.Default.TextFields,
                                        contentDescription = if (filter.isNumber) "Number, tap for text" else "Text, tap for number",
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (filter.op == FilterOp.IN) {
                AssistChip(
                    onClick = { filter.values.add("") },
                    label = { Text("Another value") },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/** A menu of [names]; types come from [columns] where one matches. */
@Composable
private fun ColumnMenu(names: List<String>, columns: List<DbColumn>, chosen: String?, none: String?, choose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(chosen?.takeIf { it.isNotEmpty() } ?: none ?: "column", fontFamily = Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            none?.let { DropdownMenuItem(text = { Text(it) }, onClick = { choose(null); open = false }) }
            names.forEach { name ->
                val type = columns.firstOrNull { it.name == name }?.typeTag.orEmpty()
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontFamily = Mono, fontSize = 13.sp)
                            if (type.isNotEmpty()) Text("  $type", fontFamily = Mono, fontSize = 10.sp, color = LocalPalette.current.faint)
                        }
                    },
                    onClick = { choose(name); open = false },
                )
            }
        }
    }
}

@Composable
private fun OpMenu(filter: BuilderFilter, engine: Engine) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(filter.op.symbol(engine), fontFamily = Mono, fontWeight = FontWeight.Bold) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FilterOp.entries.forEach { op ->
                DropdownMenuItem(
                    text = { Text(op.symbol(engine), fontFamily = Mono) },
                    onClick = { filter.op = op; open = false },
                )
            }
        }
    }
}

private fun FilterOp.symbol(engine: Engine): String = when (this) {
    FilterOp.EQUALS -> "="
    FilterOp.NOT_EQUALS -> "≠"
    FilterOp.LESS -> "<"
    FilterOp.LESS_OR_EQUAL -> "≤"
    FilterOp.GREATER -> ">"
    FilterOp.GREATER_OR_EQUAL -> "≥"
    FilterOp.LIKE -> if (engine == Engine.MONGO) "regex" else "like"
    FilterOp.LIKE_IGNORING_CASE -> when (engine) {
        Engine.POSTGRES -> "ilike"
        Engine.MY_SQL -> "like, any case"
        Engine.MONGO -> "regex, any case"
    }
    FilterOp.IN -> "in"
    FilterOp.BETWEEN -> "between"
    FilterOp.IS_NULL -> "is null"
    FilterOp.NOT_NULL -> "not null"
}

private fun likeHint(engine: Engine): String = if (engine == Engine.MONGO) "regular expression" else "pattern: % any, _ one"

/** The catalog to start a query from, searchable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablePicker(model: AppViewModel, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle("Choose a table", model.profile?.name)
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        val shown = model.catalog.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
        LazyColumn(Modifier.heightIn(max = 520.dp).navigationBarsPadding()) {
            items(shown, key = { it.schema to it.name }) { entry ->
                SheetAction(entry.name, icon = entry.kind.icon(), detail = entry.schema) {
                    onDismiss()
                    model.build(entry)
                }
            }
        }
    }
}
