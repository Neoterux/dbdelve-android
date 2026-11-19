package dev.dbdelve.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import dev.dbdelve.android.data.HistoryStore
import dev.dbdelve.android.data.Profile
import dev.dbdelve.android.data.ProfileStore
import dev.dbdelve.android.data.SavedQueryStore
import dev.dbdelve.android.data.Secrets
import dev.dbdelve.android.data.TabStore
import dev.dbdelve.android.data.UpdateStore
import dev.dbdelve.android.ui.ConnectionsScreen
import dev.dbdelve.android.ui.DBDelveTheme
import dev.dbdelve.android.ui.WorkspaceScreen
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.dbdelve_core.Engine
import uniffi.dbdelve_core.FilterOp
import uniffi.dbdelve_core.Mode
import uniffi.dbdelve_core.SslMode

/**
 * Draws the app's screens, as a phone would, into build/screens: the real
 * composables over the real core, connected to a real Postgres. Skipped
 * unless `DBDELVE_SCREENS_PG` names one as `host:port/database/user`, with
 * a blank password, and the core is built for the host.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-night-xxhdpi")
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = File(System.getProperty("dbdelve.screens") ?: "build/screens").apply { mkdirs() }

    @Test
    fun screens() {
        val target = System.getenv("DBDELVE_SCREENS_PG")
        assumeTrue("DBDELVE_SCREENS_PG is not set", target != null)
        val (address, database, user) = target!!.split('/')
        val (host, port) = address.split(':')

        val app = RuntimeEnvironment.getApplication()
        val files = File(app.filesDir, "screens-${System.nanoTime()}").apply { mkdirs() }
        // No update check: the pictures show the app, not a notice, and the test stays offline.
        val updates = UpdateStore(app).apply { automatic = false }
        val model = AppViewModel(
            ProfileStore(files),
            Secrets(app),
            TabStore(File(files, "tabs")),
            SavedQueryStore(files),
            HistoryStore(File(files, "history")),
            updates,
            installedVersion = "0.0.0",
            tlsDir = File(files, "tls"),
        ) {}
        val shop = Profile("shop", "Shop (local)", "Demo", Engine.POSTGRES, host, port.toInt(), user, database, SslMode.DISABLE, Mode.READ_ONLY)
        model.saveProfile(shop, null, null)
        model.saveProfile(shop.copy(id = "reports", name = "Reports replica", project = "Demo", mode = Mode.READ_WRITE), null, null)
        model.saveProfile(shop.copy(id = "events", name = "Events", project = "Analytics", engine = Engine.MONGO, port = 27017), null, null)

        compose.setContent {
            DBDelveTheme {
                Surface {
                    when (model.screen) {
                        Screen.Workspace -> WorkspaceScreen(model) { _, then -> then() }
                        else -> ConnectionsScreen(model)
                    }
                }
            }
        }
        shot("01-connections")

        compose.runOnIdle { model.connect(shop) }
        await { model.connectError != null || (model.session != null && model.catalog.isNotEmpty()) }
        check(model.connectError == null) { "connect failed: ${model.connectError}" }
        compose.runOnIdle {
            model.edit(model.tab!!, textAt("SELECT status, count(*), sum(total)\nFROM orders\nGROUP BY status\nORDER BY 2 DESC;"))
            model.newTab("customers", "SELECT * FROM customers WHERE country = 'EC';")
            model.newTab("Query 3", "")
            model.select(0)
        }
        shot("02-editor")

        compose.onNodeWithContentDescription("3 open tabs").performClick()
        shot("03-tab-switcher")
        compose.runOnIdle {
            model.select(1)
            model.edit(model.tab!!, textAt(model.tab!!.text.text))
            model.runAtCursor()
        }
        await { model.tab?.result != null || model.tab?.error != null }
        shot("04-results")

        compose.runOnIdle { model.pane = Pane.SCHEMA }
        shot("05-schema")

        val orders = model.catalog.first { it.name == "orders" }
        compose.runOnIdle { model.build(orders) }
        await { !model.builder.loadingColumns }
        compose.runOnIdle {
            val builder = model.builder
            builder.chosen.addAll(listOf("id", "status", "total"))
            builder.filters.add(BuilderFilter("status").apply { value = "paid" })
            builder.filters.add(BuilderFilter("total").apply { op = FilterOp.GREATER_OR_EQUAL; value = "250" })
            builder.orderBy = "total"
            builder.descending = true
            builder.limit = 100
        }
        shot("06-builder")

        compose.runOnIdle { check(model.openBuilt(run = true) == null) }
        await { model.tab?.result != null || model.tab?.error != null }
        check(model.tab?.error == null) { "the built query failed: ${model.tab?.error}" }
        shot("07-builder-results")

        compose.runOnIdle { model.pane = Pane.QUERY }
        shot("08-built-tab")

        compose.runOnIdle { model.build(orders) }
        await { !model.builder.loadingColumns }
        compose.runOnIdle {
            val builder = model.builder
            builder.chosen.addAll(listOf("id", "status", "total"))
            builder.filters.add(
                BuilderFilter("status").apply {
                    op = FilterOp.IN
                    values[0] = "paid"
                    values.add("shipped")
                },
            )
            builder.filters.add(BuilderFilter("total").apply { op = FilterOp.GREATER_OR_EQUAL; value = "800" })
            builder.groups.add(
                androidx.compose.runtime.mutableStateListOf(
                    BuilderFilter("total").apply {
                        op = FilterOp.BETWEEN
                        values[0] = "20"
                        values[1] = "25"
                    },
                ),
            )
        }
        shot("09-builder-or")

        compose.runOnIdle { model.build(orders) }
        await { !model.builder.loadingColumns }
        compose.runOnIdle {
            val builder = model.builder
            builder.count = true
            builder.groupBy.add("status")
            builder.orderBy = BuilderState.COUNT
            builder.descending = true
        }
        shot("10-builder-count")
        compose.runOnIdle { check(model.openBuilt(run = true) == null) }
        await { model.tab?.result != null || model.tab?.error != null }
        check(model.tab?.error == null) { "the grouped query failed: ${model.tab?.error}" }
        shot("11-count-results")

        // A failure, so history shows both kinds.
        compose.runOnIdle {
            model.newTab("typo", "SELECT * FROM order_itmes;")
            model.edit(model.tab!!, textAt(model.tab!!.text.text))
            model.runAtCursor()
        }
        await { model.tab?.error != null }
        check(model.history.size >= 4) { "history holds ${model.history.size}" }
        check(model.history.first().error != null && model.history.first().text.trimEnd(';') == "SELECT * FROM order_itmes") { "history begins ${model.history.first()}" }
        compose.runOnIdle { model.pane = Pane.QUERY }
        compose.onNodeWithContentDescription("History").performClick()
        shot("12-history")

        compose.runOnIdle {
            model.pane = Pane.QUERY
            model.newTab("plan", "SELECT * FROM orders WHERE status = 'paid' ORDER BY total DESC;")
            model.edit(model.tab!!, textAt(model.tab!!.text.text))
            check(model.explain(analyze = true) == null)
        }
        await { model.tab?.result != null || model.tab?.error != null }
        check(model.tab?.error == null) { "explain failed: ${model.tab?.error}" }
        check(model.tab?.text?.text == "SELECT * FROM orders WHERE status = 'paid' ORDER BY total DESC;") { "explain touched the buffer" }
        shot("13-explain")
        compose.runOnIdle { model.pane = Pane.QUERY }
        shot("14-editor-tools")

        compose.runOnIdle {
            val text = "SELECT id, total\nFROM orders\nWHERE st"
            model.newTab("complete", text)
            model.edit(model.tab!!, androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length)))
        }
        await { "orders" in model.columnCache }
        check(model.completions(model.tab!!.text.text, model.tab!!.text.text.length)?.second?.map { it.name } == listOf("status")) {
            "completion offered ${model.completions(model.tab!!.text.text, model.tab!!.text.text.length)}"
        }
        shot("17-completion")

        // Row editing, end to end: a built query on a table with a primary key.
        val customers = model.catalog.first { it.name == "customers" }
        compose.runOnIdle { model.build(customers) }
        await { !model.builder.loadingColumns }
        compose.runOnIdle {
            model.builder.chosen.addAll(listOf("id", "name", "email", "country"))
            model.builder.orderBy = "id"
            check(model.openBuilt(run = true) == null)
        }
        await { model.tab?.editTarget != null || model.tab?.notEditable != null || model.tab?.error != null }
        val edited = model.tab!!
        check(edited.editTarget?.key == listOf("id")) { "not editable: ${edited.notEditable} ${edited.error}" }
        val before = edited.result!!.rows.take(2).map { it.toList() }
        compose.runOnIdle {
            model.stageEdit(edited, 0, "email", uniffi.dbdelve_core.NewValue.Text("ana@shop.ec"))
            model.stageEdit(edited, 1, "country", uniffi.dbdelve_core.NewValue.Null)
        }
        shot("19-edits")
        compose.onNodeWithText("ana@shop.ec").performClick()
        compose.onNodeWithContentDescription("Primary key").assertExists()
        shot("18-row-edit")
        // A tap on the scrim above the sheet closes it.
        compose.onAllNodes(androidx.compose.ui.test.isRoot())[1].performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, 60f)) }
        compose.waitForIdle()
        compose.runOnIdle { check(model.reviewEdits(edited) == null) }
        shot("20-edit-review")
        compose.runOnIdle { model.applyEdits() }
        await { model.gate != null }
        shot("21-edit-gate")
        compose.runOnIdle { model.runOnceIn(Mode.READ_WRITE) }
        await { model.editMessage != null && !model.running && edited.edits.isEmpty() && edited.result?.rows?.get(0)?.get(2) == "ana@shop.ec" }
        check(model.editMessage!!.startsWith("Saved 2 rows")) { model.editMessage!! }
        check(edited.result!!.rows[1][3] == null) { "country was not set to NULL" }
        check(model.mode == Mode.READ_ONLY) { "run once changed the mode" }
        // Put them back, by the same road.
        compose.runOnIdle {
            model.stageEdit(edited, 0, "email", uniffi.dbdelve_core.NewValue.Text(before[0][2]!!))
            model.stageEdit(edited, 1, "country", uniffi.dbdelve_core.NewValue.Text(before[1][3]!!))
            check(model.reviewEdits(edited) == null)
            model.applyEdits()
        }
        await { model.gate != null }
        compose.runOnIdle { model.runOnceIn(Mode.READ_WRITE) }
        await { !model.running && edited.edits.isEmpty() && edited.result?.rows?.get(0)?.get(2) == before[0][2] }
        check(edited.result!!.rows.take(2) == before) { "not restored: ${edited.result!!.rows.take(2)}" }
    }

    /** The notice of a newer release, with its notes from GitHub. Needs no database. */
    @Test
    fun updateNotice() {
        assumeTrue("DBDELVE_SCREENS_PG is not set", System.getenv("DBDELVE_SCREENS_PG") != null)
        val app = RuntimeEnvironment.getApplication()
        val files = File(app.filesDir, "notice-${System.nanoTime()}").apply { mkdirs() }
        val updates = UpdateStore(app).apply {
            automatic = false
            found = dev.dbdelve.android.data.Updates.Release(
                "0.4.0",
                "https://github.com/Neoterux/dbdelve-android/releases/tag/v0.4.0",
                null,
                "### Added\n\n- Explain, under the editor: the plan of the statement under the cursor,\n  or the plan with timings.\n- History for each connection: every statement sent, exactly as sent.\n- TLS with your own CA, and client certificates.\n\n### Changed\n\n- A running statement keeps running when you leave the app.",
            )
        }
        val model = AppViewModel(
            ProfileStore(files),
            Secrets(app),
            TabStore(File(files, "tabs")),
            SavedQueryStore(files),
            HistoryStore(File(files, "history")),
            updates,
            installedVersion = "0.3.0",
            tlsDir = File(files, "tls"),
        ) {}
        compose.setContent { DBDelveTheme { Surface { ConnectionsScreen(model) } } }
        shot("15-update")
        compose.onNodeWithText("What's new").performClick()
        shot("16-update-notes")
    }

    /**
     * Waits for work the view model finishes on a background thread. The test
     * runs on Robolectric's main thread, so what that work posts back runs
     * only when the main looper is drained here.
     */
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        compose.waitForIdle()
    }

    private fun textAt(text: String) =
        androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(0))

    /**
     * Every open window drawn in order onto one picture, as the phone shows
     * it: a sheet is a window of its own above the screen. (PixelCopy, which
     * `captureToImage` waits on, never answers under Robolectric.)
     */
    private fun shot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val windows = (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>)
                .filter { it.isAttachedToWindow && it.visibility == View.VISIBLE && it.width > 0 }
            val screen = windows.first()
            val image = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            windows.forEach { window ->
                val params = window.layoutParams as? WindowManager.LayoutParams
                if (window !== screen && params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                }
                val at = IntArray(2).also { window.getLocationOnScreen(it) }
                canvas.save()
                canvas.translate(at[0].toFloat(), at[1].toFloat())
                window.draw(canvas)
                canvas.restore()
            }
            File(out, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
