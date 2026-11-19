package dev.dbdelve.android.data

import dev.dbdelve.android.ui.NoteBlock
import dev.dbdelve.android.ui.noteBlocks
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A release's notes come from its body, and read as the changelog does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdatesTest {
    @Test
    fun notes_come_from_the_release_body() {
        val release = Updates.parse(
            """{"tag_name": "v0.4.0", "html_url": "https://github.com/x/y/releases/tag/v0.4.0",
               "body": "### Added\r\n\r\n- **Explain**, under the\r\n  editor.\r\n- History.", "assets": []}""",
        )!!
        assertEquals("0.4.0", release.version)
        assertEquals("### Added\n\n- **Explain**, under the\n  editor.\n- History.", release.notes)
        assertEquals(
            listOf(
                NoteBlock.Heading(3, "Added"),
                NoteBlock.Bullet("Explain, under the editor."),
                NoteBlock.Bullet("History."),
            ),
            noteBlocks(release.notes),
        )
    }

    @Test
    fun a_release_without_a_body_has_no_notes() {
        val release = Updates.parse("""{"tag_name": "v1.0.0", "html_url": "https://github.com/x"}""")!!
        assertEquals("", release.notes)
    }

    @Test
    fun links_keep_their_text_and_go_nowhere() {
        assertEquals(
            listOf(NoteBlock.Paragraph("See the README for setup.")),
            noteBlocks("See the [README](https://example.com) for `setup`."),
        )
    }
}
