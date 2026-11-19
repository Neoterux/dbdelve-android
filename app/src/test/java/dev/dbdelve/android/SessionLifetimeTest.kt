package dev.dbdelve.android

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import dev.dbdelve.android.data.Profile
import dev.dbdelve.android.data.UpdateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.dbdelve_core.Engine
import uniffi.dbdelve_core.Mode
import uniffi.dbdelve_core.SslMode

/**
 * The session outlives its screen: a statement keeps running when the
 * activity is recreated or finishes, its result lands, and only once nothing
 * runs does a finished screen close the connection. Against the same
 * Postgres as [ScreensTest], skipped unless `DBDELVE_SCREENS_PG` is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionLifetimeTest {
    /** No update check from the activity: the test stays offline. */
    @Before
    fun offline() {
        UpdateStore(RuntimeEnvironment.getApplication()).automatic = false
    }

    private fun model(): AppViewModel {
        val target = System.getenv("DBDELVE_SCREENS_PG")
        assumeTrue("DBDELVE_SCREENS_PG is not set", target != null)
        val (address, database, user) = target!!.split('/')
        val (host, port) = address.split(':')
        val app = RuntimeEnvironment.getApplication() as DBDelveApp
        val model = app.model
        val profile = Profile("lifetime", "Lifetime", "", Engine.POSTGRES, host, port.toInt(), user, database, SslMode.DISABLE, Mode.READ_ONLY)
        model.saveProfile(profile, null, null)
        model.connect(profile)
        await { model.session != null || model.connectError != null }
        assertNull(model.connectError)
        return model
    }

    private fun runSlow(model: AppViewModel) {
        model.edit(model.tab!!, androidx.compose.ui.text.input.TextFieldValue("SELECT pg_sleep(2), 42 AS answer"))
        model.runAtCursor()
        assert(model.running)
    }

    @Test
    fun a_statement_outlives_a_finished_screen_then_the_session_closes() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val model = model()
        runSlow(model)
        val tab = model.tab!!
        // Back out of the app mid-statement: the activity finishes for good.
        // (Closing idles the main thread, which may outlast the statement.)
        // A session owned by the screen would have been cancelled with it,
        // and its result never delivered.
        scenario.close()
        await { !model.running }
        assertEquals("42", tab.result?.rows?.single()?.get(1))
        assertNull("a finished screen leaves no connection open once idle", model.session)
    }

    @Test
    fun a_recreated_screen_finds_the_same_session_and_its_result() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val model = model()
        val session = model.session
        runSlow(model)
        scenario.recreate()
        await { !model.running }
        assertEquals(session, model.session)
        assertEquals("42", model.tab?.result?.rows?.single()?.get(1))
        scenario.close()
        assertNull(model.session)
    }

    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }
}
