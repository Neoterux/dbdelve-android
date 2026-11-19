package dev.dbdelve.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import uniffi.dbdelve_core.Binding
import uniffi.dbdelve_core.BuiltQuery
import uniffi.dbdelve_core.CatalogEntry
import uniffi.dbdelve_core.Engine
import uniffi.dbdelve_core.FilterOp
import uniffi.dbdelve_core.ParamValue
import uniffi.dbdelve_core.QuerySpec
import uniffi.dbdelve_core.buildQuery
import uniffi.dbdelve_core.Column as DbColumn
import uniffi.dbdelve_core.Filter as DbFilter

/** One filter row: a column, how it compares, and the values typed for it. */
class BuilderFilter(column: String) {
    var column by mutableStateOf(column)
    private var _op by mutableStateOf(FilterOp.EQUALS)

    /** One value for most; from and to for between; as many as listed for in. */
    val values = mutableStateListOf("")

    var op: FilterOp
        get() = _op
        set(next) {
            _op = next
            val wanted = when (next) {
                FilterOp.IS_NULL, FilterOp.NOT_NULL -> 0
                FilterOp.BETWEEN -> 2
                FilterOp.IN -> values.size.coerceAtLeast(1)
                else -> 1
            }
            while (values.size < wanted) values.add("")
            while (values.size > wanted) values.removeAt(values.lastIndex)
        }

    /** The first value: the only one, for most comparisons. */
    var value: String
        get() = values.firstOrNull().orEmpty()
        set(text) {
            if (values.isEmpty()) values.add(text) else values[0] = text
        }

    /** Null until the user picks: then values that all read as numbers are numbers. */
    var asNumber by mutableStateOf<Boolean?>(null)

    val takesValue: Boolean get() = op != FilterOp.IS_NULL && op != FilterOp.NOT_NULL

    val isPattern: Boolean get() = op == FilterOp.LIKE || op == FilterOp.LIKE_IGNORING_CASE

    /** A pattern is always text; otherwise the user's pick, or what the values look like. */
    val isNumber: Boolean
        get() = !isPattern && (asNumber ?: values.all { NUMBER.matches(it.trim()) })

    private companion object {
        val NUMBER = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")
    }
}

/**
 * The query builder's choices. The statement itself is written by the core
 * (`buildQuery`), with every typed value as a `:name` parameter filled in by
 * `fillParameters`: nothing typed here is spliced into SQL by the app.
 */
class BuilderState(val table: CatalogEntry? = null) {
    var columns by mutableStateOf<List<DbColumn>>(emptyList())
    var loadingColumns by mutableStateOf(table != null)
    var columnsError by mutableStateOf<String?>(null)

    /** Empty means every column. */
    val chosen = mutableStateListOf<String>()

    /** Filters in groups: all of one group's must hold, any group may. Never empty. */
    val groups = mutableStateListOf(mutableStateListOf<BuilderFilter>())

    /** The first group's filters, which is all of them until an OR is added. */
    val filters: SnapshotStateList<BuilderFilter> get() = groups.first()

    var count by mutableStateOf(false)
    val groupBy = mutableStateListOf<String>()
    var orderBy by mutableStateOf<String?>(null)
    var descending by mutableStateOf(false)
    var limit by mutableStateOf<Int?>(null)

    val aggregates: Boolean get() = count || groupBy.isNotEmpty()

    /** What a sort can name: the result's columns, which grouping changes. */
    fun sortable(): List<String> = if (aggregates) groupBy + listOfNotNull(COUNT.takeIf { count }) else columns.map { it.name }

    fun removeFilter(group: SnapshotStateList<BuilderFilter>, filter: BuilderFilter) {
        group.remove(filter)
        if (group.isEmpty() && groups.size > 1) groups.remove(group)
    }

    fun built(engine: Engine): BuiltQuery? {
        val table = table ?: return null
        // A sort the result no longer has (grouping changed it) is dropped,
        // not written: it would name a column the statement doesn't return.
        val sort = orderBy?.takeIf { !aggregates || it in sortable() }
        return buildQuery(
            engine,
            QuerySpec(
                schema = table.schema,
                table = table.name,
                columns = chosen.toList(),
                groups = groups.map { group -> group.map { DbFilter(it.column, it.op, it.values.size.toUInt()) } },
                count = count,
                groupBy = groupBy.toList(),
                orderBy = sort,
                descending = descending,
                limit = limit?.toUInt(),
            ),
        )
    }

    /** Each filter's values under the parameters the core named for them. */
    fun bindings(built: BuiltQuery): List<Binding> =
        groups.flatten().zip(built.parameters).flatMap { (filter, names) ->
            names.zip(filter.values).map { (name, value) ->
                Binding(name, if (filter.isNumber) ParamValue.Number(value) else ParamValue.Text(value))
            }
        }

    companion object {
        /** The name the core gives a count, and a sort on it. */
        const val COUNT = "count"
    }
}
