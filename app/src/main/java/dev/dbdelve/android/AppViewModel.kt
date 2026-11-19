package dev.dbdelve.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.dbdelve.android.data.ClientCert
import dev.dbdelve.android.data.HistoryEntry
import dev.dbdelve.android.data.HistoryStore
import dev.dbdelve.android.data.Profile
import dev.dbdelve.android.data.ProfileStore
import dev.dbdelve.android.data.Secrets
import dev.dbdelve.android.data.ConnectionBundle
import dev.dbdelve.android.data.SavedQuery
import dev.dbdelve.android.data.SavedQueryStore
import dev.dbdelve.android.data.SharedConnection
import dev.dbdelve.android.data.TabStore
import dev.dbdelve.android.data.UpdateStore
import dev.dbdelve.android.data.Updates
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.dbdelve_core.Account
import uniffi.dbdelve_core.Binding
import uniffi.dbdelve_core.CatalogEntry
import uniffi.dbdelve_core.CoreException
import uniffi.dbdelve_core.Destructive
import uniffi.dbdelve_core.DbUser
import uniffi.dbdelve_core.Mode
import uniffi.dbdelve_core.Prepared
import uniffi.dbdelve_core.ResultSet
import uniffi.dbdelve_core.RunOutcome
import uniffi.dbdelve_core.Session
import uniffi.dbdelve_core.Stop
import uniffi.dbdelve_core.UserAction
import uniffi.dbdelve_core.Verdict
import uniffi.dbdelve_core.explainStatement
import uniffi.dbdelve_core.fillParameters
import uniffi.dbdelve_core.openConnections
import uniffi.dbdelve_core.sealConnections
import uniffi.dbdelve_core.statementAt

class QueryTab(val id: Long, title: String, text: String) {
    var title by mutableStateOf(title)
    var text by mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    var result by mutableStateOf<ResultSet?>(null)
    var error by mutableStateOf<String?>(null)
    /** What was sent, exactly, so the results say what produced them. */
    var ran by mutableStateOf<String?>(null)
}

/**
 * A statement the gate stopped, held while the user answers. Nothing was
 * sent. [retry] sends it again with the answer, through the same gate.
 */
data class PendingGate(
    val statement: String,
    val stop: Stop,
    val verdict: Verdict,
    val confirmed: List<Destructive>,
    val onceIn: Mode?,
    val retry: (confirmed: List<Destructive>, runOnce: Boolean, onceIn: Mode?) -> Unit,
)

/** A user-management statement built and waiting for the user to read it. */
class Review(val title: String, val prepared: Prepared)

sealed interface Screen {
    data object Connections : Screen
    data class Edit(val profile: Profile?, val duplicate: Boolean = false) : Screen
    data object Workspace : Screen
}

enum class Pane { QUERY, RESULTS, SCHEMA, USERS }

/** How the Query screen makes a statement: typed, or built by tapping. */
enum class QueryInput { TEXT, BUILDER }

fun Throwable.reason(): String = when (this) {
    is CoreException.Connect -> reason
    is CoreException.Query -> reason
    else -> message ?: toString()
}

/**
 * The app's state and its open session, for the whole process rather than for
 * one screen: [DBDelveApp] holds it, so an activity Android destroys (a back
 * gesture, swiping the app from recents, a reclaimed task) leaves a running
 * statement running. [QueryService] keeps the process at foreground priority
 * while one does, and a new activity picks up the same session, tabs and
 * results. When the screen goes for good, [screenGone] closes the session,
 * at once or when the statement running ends.
 */
class AppViewModel(
    private val store: ProfileStore,
    private val secrets: Secrets,
    private val tabStore: TabStore,
    private val savedStore: SavedQueryStore,
    private val historyStore: HistoryStore,
    private val updates: UpdateStore,
    /** This build's versionName, which releases are compared against. */
    val installedVersion: String,
    /**
     * App-private, out of backups: MySQL's and MongoDB's drivers read TLS
     * material only from files, which the core writes here for a session's
     * life and deletes when it closes.
     */
    private val tlsDir: File,
    /** Called with the connection's name while a statement runs, null after. */
    private val keepAlive: (connection: String?) -> Unit,
) {
    /** Lives as long as the process; the main thread, as Compose state wants. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var profiles by mutableStateOf(store.load())
        private set

    init {
        // A process that died with a session open left its TLS files; none is
        // open yet in this one.
        tlsDir.listFiles()?.forEach { it.delete() }
    }
    var screen by mutableStateOf<Screen>(Screen.Connections)
    var connecting by mutableStateOf<String?>(null)
        private set
    var connectError by mutableStateOf<String?>(null)

    var session by mutableStateOf<Session?>(null)
        private set
    var profile by mutableStateOf<Profile?>(null)
        private set
    var mode by mutableStateOf(Mode.READ_ONLY)
        private set

    val tabs = mutableStateListOf<QueryTab>()
    var current by mutableStateOf(0)
        private set
    var pane by mutableStateOf(Pane.QUERY)
    var running by mutableStateOf(false)
        private set
    var rowLimit by mutableStateOf(1000)

    var catalog by mutableStateOf<List<CatalogEntry>>(emptyList())
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set
    var gate by mutableStateOf<PendingGate?>(null)
        private set

    private var nextTabId = 0L

    val tab: QueryTab? get() = tabs.getOrNull(current)

    fun saveProfile(profile: Profile, password: String?, clientCert: ClientCert?) {
        // The password is written first: a profile saved without its password
        // would connect with a blank one, which is valid, and wrong.
        password?.let { secrets.setPassword(profile.id, it) }
        secrets.setClientCert(profile.id, clientCert)
        val next = profiles.filterNot { it.id == profile.id } + profile
        profiles = next.sortedWith(compareBy({ it.project }, { it.name }))
        store.save(profiles)
        screen = Screen.Connections
    }

    /**
     * The saved password, or blank when the Keystore can no longer open it
     * (a key invalidated by a lock-screen reset): the form then asks again
     * rather than the app crashing.
     */
    fun password(profile: Profile): String = runCatching { secrets.password(profile.id) }.getOrDefault("")

    /** The saved client certificate, or none when the Keystore can no longer open it. */
    fun clientCert(profile: Profile): ClientCert? = runCatching { secrets.clientCert(profile.id) }.getOrNull()

    fun deleteProfile(profile: Profile) {
        profiles = profiles.filterNot { it.id == profile.id }
        store.save(profiles)
        secrets.forget(profile.id)
        tabStore.forget(profile.id)
        historyStore.forget(profile.id)
        allSaved = allSaved.filterNot { it.profileId == profile.id }
        savedStore.save(allSaved)
    }

    /**
     * The chosen connections sealed into a file under [passphrase]; with their
     * passwords only when asked. Slow on purpose (the key derivation), so off
     * the main thread.
     */
    suspend fun exportConnections(chosen: List<Profile>, withPasswords: Boolean, passphrase: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val shared = chosen.map {
                    SharedConnection(it, if (withPasswords) password(it) else null, if (withPasswords) clientCert(it) else null)
                }
                sealConnections(ConnectionBundle.toJson(shared), passphrase)
            }
        }

    /** Opens a shared file: a wrong passphrase or a changed byte fails here. */
    suspend fun openConnectionsFile(file: ByteArray, passphrase: String): Result<List<SharedConnection>> =
        withContext(Dispatchers.IO) {
            runCatching { ConnectionBundle.fromJson(openConnections(file, passphrase)) }
                .recoverCatching { throw IllegalStateException(it.reason()) }
        }

    enum class Clash { KEEP_BOTH, REPLACE, SKIP }

    /**
     * Saves what the import dialog settled on. A name already here is kept
     * beside it, replaced in place (its id, tabs and saved queries kept), or
     * skipped, as chosen per connection.
     */
    fun importConnections(chosen: List<Pair<SharedConnection, Clash>>): Int {
        var imported = 0
        var next = profiles
        chosen.forEach { (shared, clash) ->
            val existing = next.find { it.name == shared.profile.name }
            val profile = when {
                existing == null -> shared.profile
                clash == Clash.SKIP -> return@forEach
                clash == Clash.REPLACE -> shared.profile.copy(id = existing.id)
                else -> shared.profile.copy(name = uniqueName(shared.profile.name, next))
            }
            shared.password?.let { secrets.setPassword(profile.id, it) }
            shared.clientCert?.let { secrets.setClientCert(profile.id, it) }
            next = next.filterNot { it.id == profile.id } + profile
            imported++
        }
        profiles = next.sortedWith(compareBy({ it.project }, { it.name }))
        store.save(profiles)
        return imported
    }

    private fun uniqueName(name: String, taken: List<Profile>): String =
        generateSequence(2) { it + 1 }.map { "$name ($it)" }.first { candidate -> taken.none { it.name == candidate } }

    private var allSaved by mutableStateOf(savedStore.load())

    /** The open connection's saved queries, by name. */
    val saved: List<SavedQuery>
        get() = profile?.let { open -> allSaved.filter { it.profileId == open.id }.sortedBy { it.name.lowercase() } }.orEmpty()

    /** Saves [text] as [name] for this connection, replacing one of that name. */
    fun saveQuery(name: String, text: String, fromTab: Boolean = true) {
        val profileId = profile?.id ?: return
        val kept = allSaved.filterNot { it.profileId == profileId && it.name == name }
        allSaved = kept + SavedQuery(SavedQueryStore.newId(), profileId, name, text)
        savedStore.save(allSaved)
        if (fromTab) {
            tab?.title = name
            saveTabs()
        }
    }

    fun deleteSaved(query: SavedQuery) {
        allSaved = allSaved.filterNot { it.id == query.id }
        savedStore.save(allSaved)
    }

    /** Opens a saved query in a tab of its own, as saved, to edit or run. */
    fun openSaved(query: SavedQuery) {
        newTab(query.name, query.text)
        pane = Pane.QUERY
    }

    /**
     * Fills a saved query's parameters and runs it, in a tab that shows the
     * filled statement: what ran is on screen, and runs through the gate like
     * anything typed. Returns why it could not be filled, or null.
     */
    fun runSaved(query: SavedQuery, bindings: List<Binding>): String? {
        val session = session ?: return "Not connected."
        val filled = runCatching { fillParameters(session.engine(), query.text, bindings) }
            .getOrElse { return it.reason() }
        val tab = newTab(query.name, filled)
        execute(tab, filled, emptyList(), runOnce = false, onceIn = null)
        return null
    }

    fun connect(profile: Profile) {
        if (connecting != null) return
        connecting = profile.id
        connectError = null
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val config = profile.config(secrets.password(profile.id), secrets.clientCert(profile.id), tlsDir.path)
                    Session.connect(config, profile.mode)
                }
            }
            connecting = null
            opened.onSuccess { opened ->
                session = opened
                this@AppViewModel.profile = profile
                mode = profile.mode
                restoreTabs(profile)
                history = withContext(Dispatchers.IO) { historyStore.load(profile.id) }
                builder = BuilderState()
                input = QueryInput.TEXT
                pane = Pane.QUERY
                screen = Screen.Workspace
                refreshCatalog()
            }.onFailure { connectError = "${profile.name}: ${it.reason()}" }
        }
    }

    fun disconnect() {
        saveTabs(now = true)
        val closing = session
        session = null
        running = false
        profile = null
        catalog = emptyList()
        columnCache.clear()
        history = emptyList()
        gate = null
        users = emptyList()
        grants = emptyMap()
        review = null
        userMessage = null
        screen = Screen.Connections
        scope.launch(Dispatchers.IO) { closing?.close() }
    }

    fun newTab(title: String = "Query ${nextTabId + 1}", text: String = ""): QueryTab {
        val tab = QueryTab(nextTabId++, title, text)
        tabs.add(tab)
        current = tabs.lastIndex
        saveTabs()
        return tab
    }

    fun closeTab(tab: QueryTab) {
        tabs.remove(tab)
        if (tabs.isEmpty()) newTab()
        current = current.coerceIn(0, tabs.lastIndex)
        saveTabs()
    }

    fun select(at: Int) {
        current = at
        saveTabs()
    }

    /** Every keystroke and key-row press goes through here, so none is unsaved. */
    fun edit(tab: QueryTab, value: TextFieldValue) {
        val changed = tab.text.text != value.text
        tab.text = value
        if (changed) saveTabs()
    }

    private fun restoreTabs(profile: Profile) {
        tabs.clear()
        nextTabId = 0
        val (saved, at) = tabStore.load(profile.id)
        if (saved.isEmpty()) {
            newTab()
            return
        }
        saved.forEach { tabs.add(QueryTab(nextTabId++, it.title, it.text)) }
        current = at.coerceIn(0, tabs.lastIndex)
    }

    private var saving: Job? = null

    /**
     * Writes the tabs a moment after the last change, so typing is not a
     * write per keystroke; [now] writes at once, for leaving the connection.
     */
    private fun saveTabs(now: Boolean = false) {
        val id = profile?.id ?: return
        val snapshot = tabs.map { TabStore.Saved(it.title, it.text.text) }
        val at = current
        saving?.cancel()
        saving = scope.launch {
            if (!now) delay(600)
            withContext(Dispatchers.IO) { tabStore.save(id, snapshot, at) }
        }
    }

    /** The open connection's statements as sent, newest first. */
    var history by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    /** Keeps what was sent and what came of it, for this connection. */
    private fun record(statement: String, sent: Long, result: ResultSet?, error: String?) {
        val id = profile?.id ?: return
        val entry = HistoryEntry(
            text = statement,
            at = sent,
            elapsedMs = result?.elapsedMs?.toLong(),
            rows = result?.takeIf { it.columns.isNotEmpty() }?.rows?.size,
            capped = result?.capped == true,
            affected = result?.affected?.toLong(),
            error = error?.take(HistoryStore.ERROR_CHARS),
        )
        // A statement run again moves to the top rather than filling the list.
        history = (listOf(entry) + history.filterNot { it.text == statement }).take(HistoryStore.LIMIT)
        val snapshot = history
        scope.launch(Dispatchers.IO) { historyStore.save(id, snapshot) }
    }

    fun clearHistory() {
        val id = profile?.id ?: return
        history = emptyList()
        scope.launch(Dispatchers.IO) { historyStore.forget(id) }
    }

    /** A statement from history in a tab of its own, to read, edit or run. */
    fun openFromHistory(entry: HistoryEntry) {
        newTab("History", entry.text)
        input = QueryInput.TEXT
        pane = Pane.QUERY
    }

    /**
     * Explains the statement under the cursor: `EXPLAIN` (or MongoDB's
     * `.explain()`) on a copy, sent like anything typed, its plan shown as
     * this tab's result. The text in the editor is never touched. With
     * [analyze] it runs the statement for its timings, and the gate then
     * treats it as the statement itself. Returns why it could not, or null.
     */
    fun explain(analyze: Boolean): String? {
        val tab = tab ?: return null
        val session = session ?: return "Not connected."
        val text = tab.text.text
        val span = statementAt(session.engine(), text, tab.text.selection.start.toUInt()) ?: return "Nothing to explain."
        val statement = text.substring(span.start.toInt(), span.end.toInt())
        val explained = runCatching { explainStatement(session.engine(), statement, analyze) }.getOrElse { return it.reason() }
        execute(tab, explained, emptyList(), runOnce = false, onceIn = null)
        return null
    }

    /** Runs the statement under the cursor, as typed. */
    fun runAtCursor() {
        val tab = tab ?: return
        val session = session ?: return
        val text = tab.text.text
        val span = statementAt(session.engine(), text, tab.text.selection.start.toUInt()) ?: return
        execute(tab, text.substring(span.start.toInt(), span.end.toInt()), emptyList(), runOnce = false, onceIn = null)
    }

    private fun execute(
        tab: QueryTab,
        statement: String,
        confirmed: List<Destructive>,
        runOnce: Boolean,
        onceIn: Mode?,
    ) {
        val limit = rowLimit.toUInt()
        val sent = System.currentTimeMillis()
        send(
            shown = statement,
            confirmed = confirmed,
            onceIn = onceIn,
            retry = { again, once, mode -> execute(tab, statement, again, once, mode) },
            call = { it.run(statement, limit, confirmed, runOnce, onceIn) },
            done = { result ->
                record(statement, sent, result, null)
                tab.result = result
                tab.error = null
                tab.ran = statement
                pane = Pane.RESULTS
            },
            failed = { reason ->
                record(statement, sent, null, reason)
                tab.error = reason
                tab.ran = statement
                pane = Pane.RESULTS
            },
        )
    }

    /** Sends one statement through the gate; a stop becomes [gate]. */
    private fun send(
        shown: String,
        confirmed: List<Destructive>,
        onceIn: Mode?,
        retry: (List<Destructive>, Boolean, Mode?) -> Unit,
        call: (Session) -> RunOutcome,
        done: (ResultSet) -> Unit,
        failed: (String) -> Unit,
    ) {
        val session = session ?: return
        if (running) return
        running = true
        keepAlive(profile?.name)
        scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { runCatching { call(session) } }
            } finally {
                // However it ends: otherwise the foreground service outlives it.
                keepAlive(null)
                if (this@AppViewModel.session === session) running = false
            }
            // A statement that ends after a disconnect, or after switching to
            // another connection, must not land in the new one's state.
            if (this@AppViewModel.session !== session) return@launch
            outcome.onSuccess { outcome ->
                when (outcome) {
                    is RunOutcome.Done -> done(outcome.result)
                    is RunOutcome.Stopped ->
                        gate = PendingGate(shown, outcome.stop, outcome.verdict, confirmed, onceIn, retry)
                }
            }.onFailure { failed(it.reason()) }
            // The screen went while this ran: its outcome is kept (history),
            // and now nothing is left to keep the connection open for.
            if (closeWhenIdle) {
                closeWhenIdle = false
                disconnect()
            }
        }
    }

    fun dismissGate() {
        gate = null
    }

    /** Runs the stopped statement once in [mode]; the connection's own mode stays. */
    fun runOnceIn(mode: Mode) {
        val pending = gate ?: return
        gate = null
        pending.retry(pending.confirmed, false, mode)
    }

    fun confirm(kind: Destructive) {
        val pending = gate ?: return
        gate = null
        pending.retry(pending.confirmed + kind, false, pending.onceIn)
    }

    fun runUnreadableOnce() {
        val pending = gate ?: return
        gate = null
        pending.retry(pending.confirmed, true, pending.onceIn)
    }

    /** Switches the connection, then runs what was stopped. */
    fun switchAndRun(mode: Mode) {
        val pending = gate ?: return
        gate = null
        setMode(mode) { pending.retry(pending.confirmed, false, null) }
    }

    var users by mutableStateOf<List<DbUser>>(emptyList())
        private set
    var usersError by mutableStateOf<String?>(null)
        private set
    var grants by mutableStateOf<Map<Account, List<String>>>(emptyMap())
        private set
    var review by mutableStateOf<Review?>(null)
        private set
    /** What the last user-management statement did, or why it failed. */
    var userMessage by mutableStateOf<String?>(null)

    var refreshingUsers by mutableStateOf(false)
        private set

    fun refreshUsers() {
        val session = session ?: return
        refreshingUsers = true
        scope.launch {
            val listed = withContext(Dispatchers.IO) { runCatching { session.users() } }
            refreshingUsers = false
            listed.onSuccess {
                users = it
                usersError = null
            }.onFailure { usersError = it.reason() }
        }
    }

    fun loadGrants(account: Account) {
        val session = session ?: return
        scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { session.userGrants(account) } }
            grants = grants + (account to read.getOrElse { listOf(it.reason()) })
        }
    }

    /** Builds the statement for [action] and holds it for the user to read. */
    fun prepare(title: String, action: UserAction) {
        val session = session ?: return
        scope.launch {
            val built = withContext(Dispatchers.IO) { runCatching { session.prepareUser(action) } }
            built.onSuccess { review = Review(title, it) }
                .onFailure { userMessage = it.reason() }
        }
    }

    fun dismissReview() {
        review?.prepared?.close()
        review = null
    }

    /**
     * Runs the reviewed statement. The review named any destructive kind, so
     * reading it is the confirmation; a mode it needs still goes through the
     * gate's own dialog.
     */
    fun runReviewed() {
        val reviewed = review ?: return
        review = null
        runPrepared(reviewed, reviewed.prepared.verdict().destructive, null)
    }

    private fun runPrepared(reviewed: Review, confirmed: List<Destructive>, onceIn: Mode?) {
        send(
            shown = reviewed.prepared.shown(),
            confirmed = confirmed,
            onceIn = onceIn,
            retry = { again, _, mode -> runPrepared(reviewed, again, mode) },
            call = { it.runPrepared(reviewed.prepared, confirmed, onceIn) },
            done = {
                userMessage = "${reviewed.title}: done."
                refreshUsers()
                grants.keys.forEach(::loadGrants)
            },
            failed = { userMessage = "${reviewed.title}: $it" },
        )
    }

    /** The only place a connection's mode changes, and it is saved with it. */
    fun setMode(next: Mode, then: () -> Unit = {}) {
        val session = session ?: return
        val profile = profile ?: return
        scope.launch {
            val changed = withContext(Dispatchers.IO) { runCatching { session.setMode(next) } }
            // It may complete after the user left this connection: the
            // profile still takes the mode, the screen of another does not.
            val current = this@AppViewModel.session === session
            changed.onSuccess {
                val saved = profile.copy(mode = next)
                profiles = profiles.map { if (it.id == saved.id) saved else it }
                store.save(profiles)
                if (!current) return@onSuccess
                mode = next
                this@AppViewModel.profile = saved
                then()
            }.onFailure { failure -> if (current) tab?.let { it.error = failure.reason() } }
        }
    }

    fun cancel() {
        val session = session ?: return
        // A thread of its own: the session's lock is held by what it stops.
        // A disconnect may destroy the session first; a cancel then has
        // nothing left to stop, and must not take the app down trying.
        Thread { runCatching { session.cancel() } }.start()
    }

    var refreshingCatalog by mutableStateOf(false)
        private set

    fun refreshCatalog() {
        val session = session ?: return
        refreshingCatalog = true
        scope.launch {
            val listed = withContext(Dispatchers.IO) { runCatching { session.catalog() } }
            refreshingCatalog = false
            listed.onSuccess {
                catalog = it
                // Tables may have changed: their columns are read again as named.
                columnCache.clear()
                catalogError = null
            }.onFailure { catalogError = it.reason() }
        }
    }

    /** Column names by table name, read as the editor's text names tables. */
    val columnCache = mutableStateMapOf<String, List<String>>()
    private val columnsLoading = mutableSetOf<String>()

    /** Names to complete the word at the cursor with: see [Completion]. */
    fun completions(text: String, cursor: Int): Pair<Completion.Word, List<Completion.Suggestion>>? {
        val engine = session?.engine() ?: return null
        val word = Completion.word(text, cursor) ?: return null
        val tables = catalog.map { it.name }.distinct()
        return word to Completion.suggest(engine, text, word, tables, columnsOf = { columnCache[it] })
    }

    /**
     * Reads the columns of the tables [text] names, each once per connection,
     * so completion can offer them. Not while a statement runs: the session
     * would only queue the read behind it.
     */
    fun loadColumnsFor(text: String) {
        val session = session ?: return
        if (running) return
        val engine = session.engine()
        val names = catalog.map { it.name }.distinct()
        val wanted = Completion.referenced(engine, text, names).toMutableSet()
        Completion.word(text, text.length)?.qualifier?.let { alias -> Completion.aliased(text, alias, names)?.let(wanted::add) }
        for (name in wanted) {
            if (name in columnCache || !columnsLoading.add(name)) continue
            val entry = catalog.firstOrNull { it.name == name } ?: continue
            scope.launch {
                val read = withContext(Dispatchers.IO) { runCatching { session.columns(entry.schema, entry.name) } }
                columnsLoading.remove(name)
                if (this@AppViewModel.session !== session) return@launch
                // A failed read leaves nothing to offer, quietly: completion is a help.
                columnCache[name] = read.getOrNull()?.map { it.name }.orEmpty()
            }
        }
    }

    var input by mutableStateOf(QueryInput.TEXT)

    /** The builder's choices for this connection, kept while it is open. */
    var builder by mutableStateOf(BuilderState())
        private set

    /** Starts the builder on [entry], reading its columns to choose from. */
    fun build(entry: CatalogEntry) {
        val session = session ?: return
        val state = BuilderState(entry)
        builder = state
        input = QueryInput.BUILDER
        pane = Pane.QUERY
        scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { session.columns(entry.schema, entry.name) } }
            if (builder !== state) return@launch
            state.loadingColumns = false
            read.onSuccess { state.columns = it }.onFailure { state.columnsError = it.reason() }
        }
    }

    /**
     * The builder's statement with its values filled in, in a tab of its own:
     * what runs is on screen and passes the gate like anything typed. Returns
     * why it could not be filled, or null.
     */
    fun openBuilt(run: Boolean): String? {
        val session = session ?: return "Not connected."
        val entry = builder.table ?: return "Choose a table first."
        val built = builder.built(session.engine()) ?: return "Choose a table first."
        val filled = runCatching { fillParameters(session.engine(), built.text, builder.bindings(built)) }
            .getOrElse { return it.reason() }
        val tab = newTab(entry.name, filled)
        input = QueryInput.TEXT
        if (run) execute(tab, filled, emptyList(), runOnce = false, onceIn = null) else pane = Pane.QUERY
        return null
    }

    /** Saves the builder's statement with its `:name` parameters, asked for at each run. */
    fun saveBuilt(name: String) {
        val engine = session?.engine() ?: return
        val built = builder.built(engine) ?: return
        saveQuery(name, built.text, fromTab = false)
    }

    /** DBDelve's own preview query, in a tab of its own where it can be read. */
    fun preview(entry: CatalogEntry) {
        val session = session ?: return
        val text = session.preview(entry.schema, entry.name, rowLimit.toUInt())
        val tab = newTab(entry.name, text)
        execute(tab, text, emptyList(), runOnce = false, onceIn = null)
    }

    /** A newer release than this build, not put off by the user. */
    var update by mutableStateOf(updates.found?.takeIf(::worthShowing))
        private set
    var checkingUpdates by mutableStateOf(false)
        private set
    var automaticUpdates by mutableStateOf(updates.automatic)
        private set

    private fun worthShowing(release: Updates.Release) =
        Updates.isNewer(release.version, installedVersion) && release.version != updates.dismissed

    fun changeAutomaticUpdates(on: Boolean) {
        updates.automatic = on
        automaticUpdates = on
        if (on) checkForUpdates(manual = false)
    }

    /**
     * Asks GitHub for the latest release. By itself (on launch) at most once
     * every [UPDATE_INTERVAL], silently, and not at all when turned off; on
     * request, always, and says what it found, including nothing.
     */
    fun checkForUpdates(manual: Boolean) {
        if (checkingUpdates) return
        if (!manual && (!updates.automatic || System.currentTimeMillis() - updates.lastCheck < UPDATE_INTERVAL)) return
        checkingUpdates = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updates.fetchLatest() } }
            checkingUpdates = false
            result.onSuccess { latest ->
                updates.lastCheck = System.currentTimeMillis()
                val newer = latest?.takeIf { Updates.isNewer(it.version, installedVersion) }
                updates.found = newer
                // Asking by hand brings back a version put off earlier.
                if (manual && newer != null && newer.version == updates.dismissed) updates.dismissed = null
                update = newer?.takeIf(::worthShowing)
                if (manual && newer == null) connectError = "DBDelve $installedVersion is the latest version."
            }.onFailure { error ->
                if (manual) connectError = "Couldn't check for updates: ${error.reason()}"
            }
        }
    }

    /** Hides the notice until a later version than this one is out. */
    fun dismissUpdate() {
        update?.let { updates.dismissed = it.version }
        update = null
    }

    /** Set when the screen went for good mid-statement: disconnect once it ends. */
    private var closeWhenIdle = false

    /** A screen is showing this state again: whatever it holds stays open. */
    fun screenShown() {
        closeWhenIdle = false
    }

    /**
     * The last screen finished for good (not a rotation). A statement running
     * runs on, and the session closes when it ends; otherwise now, so a
     * cached process does not hold a server connection nobody can see.
     */
    fun screenGone() {
        if (session == null) return
        if (running) closeWhenIdle = true else disconnect()
    }

    private companion object {
        const val UPDATE_INTERVAL = 12L * 60 * 60 * 1000
    }
}
