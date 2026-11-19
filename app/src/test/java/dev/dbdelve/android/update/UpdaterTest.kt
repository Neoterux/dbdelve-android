package dev.dbdelve.android.update

import android.content.Intent
import android.content.pm.Signature
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The checks a downloaded update passes before Android's installer sees it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdaterTest {
    @Test
    fun the_copy_is_hashed_as_it_goes() {
        val out = ByteArrayOutputStream()
        var last = 0L
        val hash = Updater.copyHashing(ByteArrayInputStream("abc".toByteArray()), out) { last = it }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertEquals("abc", out.toString())
        assertEquals(3L, last)
    }

    @Test
    fun the_signer_must_match_or_be_rotated_from() {
        val old = Signature(byteArrayOf(1, 2, 3))
        val new = Signature(byteArrayOf(4, 5, 6))
        assertTrue(Updater.sameSigner(listOf(old), listOf(old)))
        // The download was signed with a new key that names ours in its history.
        assertTrue(Updater.sameSigner(listOf(old), listOf(old, new)))
        assertFalse(Updater.sameSigner(listOf(old), listOf(new)))
        assertFalse(Updater.sameSigner(listOf(old, new), listOf(old)))
    }

    @Test
    fun a_download_that_is_not_an_apk_is_refused() {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "not-an.apk").apply { writeText("<html>rate limited</html>") }
        assertNotNull(Updater.refusal(app, file, "0.5.0"))
    }

    @Test
    fun the_installer_gets_the_file_through_the_provider() {
        val app = RuntimeEnvironment.getApplication()
        val file = Updater.apkFile(app, "0.6.0").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals("dbdelve-android-0.6.0.apk", file.name)
        val intent = Updater.installIntent(app, file)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun a_version_cannot_leave_the_updates_folder() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(Updater.folder(app), Updater.apkFile(app, "../../x").parentFile)
    }
}
