package dev.dbdelve.android.ui

/** A block of release notes or changelog, as the app draws it. */
sealed interface NoteBlock {
    data class Heading(val level: Int, val text: String) : NoteBlock
    data class Bullet(val text: String) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
}

/**
 * The little Markdown the changelog uses, in blocks: `##`/`###` headings,
 * `-` bullets whose wrapped lines continue them, and paragraphs. Emphasis
 * and code marks are dropped and links keep their text: the notes are read,
 * not followed.
 */
fun noteBlocks(markdown: String): List<NoteBlock> {
    val blocks = mutableListOf<NoteBlock>()
    var open: StringBuilder? = null
    var bullet = false
    fun close() {
        open?.let { text -> blocks += if (bullet) NoteBlock.Bullet(plain(text.toString())) else NoteBlock.Paragraph(plain(text.toString())) }
        open = null
    }
    for (raw in markdown.lines()) {
        val line = raw.trimEnd()
        val heading = Regex("^(#{1,6})\\s+(.*)").find(line)
        when {
            line.isBlank() -> close()
            heading != null -> {
                close()
                blocks += NoteBlock.Heading(heading.groupValues[1].length, plain(heading.groupValues[2]))
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                close()
                bullet = true
                open = StringBuilder(line.trimStart().drop(2))
            }
            open != null -> open!!.append(' ').append(line.trim())
            else -> {
                bullet = false
                open = StringBuilder(line.trim())
            }
        }
    }
    close()
    return blocks
}

private fun plain(text: String): String = text
    .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
    .replace("**", "")
    .replace("`", "")
