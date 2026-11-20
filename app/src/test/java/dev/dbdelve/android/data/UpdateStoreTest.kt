package dev.dbdelve.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What's new opens once after an update, never on an ordinary launch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateStoreTest {
    private fun fresh() = UpdateStore(RuntimeEnvironment.getApplication()).apply {
        lastRun = null
        changelogPending = false
    }

    @Test
    fun a_new_install_shows_nothing() {
        val store = fresh()
        store.noteLaunch("0.6.1", updatedSinceInstall = false)
        assertFalse(store.changelogPending)
        assertEquals("0.6.1", store.lastRun)
    }

    @Test
    fun the_first_launch_after_updating_from_a_build_that_kept_no_record() {
        val store = fresh()
        store.noteLaunch("0.6.1", updatedSinceInstall = true)
        assertTrue(store.changelogPending)
    }

    @Test
    fun an_update_shows_it_once_and_a_relaunch_does_not() {
        val store = fresh()
        store.noteLaunch("0.6.1", updatedSinceInstall = true)
        store.changelogPending = false // shown and closed
        store.noteLaunch("0.6.1", updatedSinceInstall = true)
        assertFalse(store.changelogPending)
        store.noteLaunch("0.7.0", updatedSinceInstall = true)
        assertTrue(store.changelogPending)
    }

    @Test
    fun not_shown_and_not_lost_until_closed() {
        val store = fresh()
        store.noteLaunch("0.6.1", updatedSinceInstall = true)
        store.noteLaunch("0.6.1", updatedSinceInstall = true)
        assertTrue(store.changelogPending)
    }
}
