package dev.dbdelve.android

import uniffi.dbdelve_core.Engine

/**
 * Names to complete the word at the cursor with: the connection's tables
 * (or collections) from the schema list, and the columns of the tables the
 * text names, read with `Session::columns`. Only names that exist; no
 * keywords, no guesses.
 *
 * Plain Kotlin, no Android, so it is tested on its own.
 */
object Completion {
    /** What is being typed at the cursor, and where it starts. */
    data class Word(
        /** The part before the dot, when there is one: a table or alias. */
        val qualifier: String?,
        val prefix: String,
        /** Where [prefix] starts in the text: a choice replaces from here to the cursor. */
        val start: Int,
    )

    data class Suggestion(val name: String, val kind: Kind, val insert: String)

    enum class Kind { TABLE, COLUMN }

    private fun isNamePart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    /** The word ending at [cursor], or null when the cursor is not at the end of one. */
    fun word(text: String, cursor: Int): Word? {
        if (cursor < 0 || cursor > text.length) return null
        if (cursor < text.length && isNamePart(text[cursor])) return null
        var start = cursor
        while (start > 0 && isNamePart(text[start - 1])) start--
        val prefix = text.substring(start, cursor)
        var qualifier: String? = null
        if (start > 0 && text[start - 1] == '.') {
            var q = start - 1
            while (q > 0 && isNamePart(text[q - 1])) q--
            qualifier = text.substring(q, start - 1).takeIf { it.isNotEmpty() }
        }
        // A bare word needs a letter to go on; after a dot, nothing yet is fine.
        if (prefix.isEmpty() && qualifier == null) return null
        if (prefix.firstOrNull()?.isDigit() == true) return null
        return Word(qualifier, prefix, start)
    }

    /**
     * The tables [text] names, by any of [tables]' names as a word in it; on
     * MongoDB, as `db.<name>` or `getCollection('<name>')`.
     */
    fun referenced(engine: Engine, text: String, tables: List<String>): List<String> = tables.filter { name ->
        if (engine == Engine.MONGO) {
            text.contains("db.$name.") || text.contains("getCollection('$name')") || text.contains("getCollection(\"$name\")")
        } else {
            Regex("(?<![\\w$])${Regex.escape(name)}(?![\\w$])", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
    }

    /** The table an alias stands for: `FROM orders o`, `JOIN orders AS o`. */
    fun aliased(text: String, alias: String, tables: List<String>): String? {
        tables.firstOrNull { it.equals(alias, ignoreCase = true) }?.let { return it }
        val pattern = Regex("([\\w$]+)\\s+(?:AS\\s+)?${Regex.escape(alias)}(?![\\w$])", RegexOption.IGNORE_CASE)
        return pattern.findAll(text).mapNotNull { match ->
            tables.firstOrNull { it.equals(match.groupValues[1], ignoreCase = true) }
        }.firstOrNull()
    }

    /**
     * What to offer for [word]. After `alias.`, that table's columns; on
     * MongoDB after `db.`, collections; otherwise columns of the tables the
     * text names, then tables. Prefix matches only, ignoring case.
     */
    fun suggest(
        engine: Engine,
        text: String,
        word: Word,
        tables: List<String>,
        columnsOf: (String) -> List<String>?,
        limit: Int = 12,
    ): List<Suggestion> {
        val matches = { name: String -> name.startsWith(word.prefix, ignoreCase = true) && name != word.prefix }
        val tableSuggestions = { tables.filter(matches).map { Suggestion(it, Kind.TABLE, quote(engine, it, table = true)) } }
        val columnSuggestions = { names: List<String> ->
            names.filter(matches).distinct().map { Suggestion(it, Kind.COLUMN, quote(engine, it, table = false)) }
        }
        val found = when {
            engine == Engine.MONGO && word.qualifier == "db" -> tableSuggestions()
            word.qualifier != null && engine != Engine.MONGO -> {
                val table = aliased(text, word.qualifier, tables)
                // A schema before the dot: its tables.
                if (table == null) tableSuggestions() else columnSuggestions(columnsOf(table).orEmpty())
            }
            word.prefix.isEmpty() -> emptyList()
            else -> columnSuggestions(referenced(engine, text, tables).flatMap { columnsOf(it).orEmpty() }) + tableSuggestions()
        }
        return found.distinctBy { it.insert }.take(limit)
    }

    /**
     * A name as it must be typed: as is where the engine reads it back the
     * same unquoted, quoted otherwise. Postgres folds unquoted names to
     * lower case, so `Orders` needs its quotes.
     */
    fun quote(engine: Engine, name: String, table: Boolean): String = when (engine) {
        Engine.POSTGRES -> if (Regex("[a-z_][a-z0-9_$]*").matches(name) && name.uppercase() !in RESERVED) name else "\"" + name.replace("\"", "\"\"") + "\""
        Engine.MY_SQL -> if (Regex("[A-Za-z_][A-Za-z0-9_$]*").matches(name) && name.uppercase() !in RESERVED) name else "`" + name.replace("`", "``") + "`"
        // A collection after `db.` must be an identifier; anything else is
        // written whole, as `getCollection`, by the user.
        Engine.MONGO -> name
    }

    /** Words that cannot be a bare name in either dialect's common use. */
    private val RESERVED = setOf(
        "ALL", "AND", "AS", "ASC", "BETWEEN", "BY", "CASE", "CHECK", "COLUMN", "CONSTRAINT", "CREATE", "DEFAULT",
        "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "END", "FOR", "FOREIGN", "FROM", "GRANT", "GROUP", "HAVING",
        "IN", "INDEX", "INSERT", "INTO", "IS", "JOIN", "KEY", "LEFT", "LIKE", "LIMIT", "NOT", "NULL", "ON", "OR",
        "ORDER", "PRIMARY", "REFERENCES", "RIGHT", "SELECT", "SET", "TABLE", "THEN", "TO", "UNION", "UNIQUE",
        "UPDATE", "USER", "USING", "VALUES", "WHEN", "WHERE", "WITH",
    )
}
