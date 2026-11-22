package io.github.rmac0001.foursight.desktop.obsidian

/**
 * Edits a Markdown note's text in place (spec: Obsidian writer): only the frontmatter fields
 * FourSight owns and the `## 7-day numbers` block. Every other line, including line endings, stays
 * exactly as it was, and running it twice with the same input changes nothing.
 */
object NoteEditor {
    const val BLOCK_HEADING = "## 7-day numbers"

    /** Lines in the block that Roger fills in; their text is kept when the block is rewritten. */
    val ROGER_LINES =
        listOf(
            "- Most viewers stopped at:",
            "- Comments with substance (count + best example):",
            "- One lesson:",
        )

    private fun newline(text: String) = if ("\r\n" in text) "\r\n" else "\n"

    /** The frontmatter's lines (between the opening and closing `---`), or null when there's none. */
    private fun frontmatterRange(lines: List<String>): IntRange? {
        if (lines.firstOrNull()?.trimEnd() != "---") return null
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return null
        return 1 until end
    }

    /** Top-level `key: value` pairs from the frontmatter; values are unquoted, comments dropped. */
    fun frontmatter(text: String): Map<String, String> {
        val lines = text.split(newline(text))
        val range = frontmatterRange(lines) ?: return emptyMap()
        return range
            .map { lines[it] }
            .filter { it.isNotEmpty() && !it[0].isWhitespace() && ':' in it && !it.startsWith("#") }
            .associate { line ->
                val key = line.substringBefore(':').trim()
                var value = line.substringAfter(':').trim()
                if (!value.startsWith('"') && !value.startsWith('\'')) value = value.substringBefore(" #").trim()
                key to value.removeSurrounding("\"").removeSurrounding("'")
            }
    }

    /**
     * Sets top-level frontmatter fields. An existing `key:` line is replaced in place; a new one is
     * added just before the closing `---`. A null value writes an empty field (YAML null), so a
     * missing number reads as missing, never 0. A note without frontmatter gets one.
     */
    fun setFields(
        text: String,
        fields: Map<String, String?>,
    ): String {
        if (fields.isEmpty()) return text
        val nl = newline(text)
        val lines = text.split(nl).toMutableList()
        val range =
            frontmatterRange(lines) ?: run {
                lines.addAll(0, listOf("---", "---"))
                1 until 1
            }
        var closing = range.last + 1

        fun render(
            key: String,
            value: String?,
        ) = if (value == null) "$key:" else "$key: $value"
        fields.forEach { (key, value) ->
            val at =
                (1 until closing).firstOrNull {
                    val line = lines[it]
                    line.isNotEmpty() && !line[0].isWhitespace() && line.substringBefore(':').trimEnd() == key
                }
            if (at != null) {
                // A list or block value under the key would be orphaned; FourSight's own fields never have one.
                lines[at] = render(key, value)
            } else {
                lines.add(closing, render(key, value))
                closing++
            }
        }
        return lines.joinToString(nl)
    }

    /**
     * Replaces the note's 7-day block with [block] (FourSight's rendering), keeping Roger's lines
     * where he has written something after the colon, and any other lines he added to the block. A
     * note without the block gets it at the end.
     */
    fun upsertBlock(
        text: String,
        block: String,
    ): String {
        val nl = newline(text)
        val lines = text.split(nl).toMutableList()
        val newLines = block.split("\n").toMutableList()
        val start = lines.indexOfFirst { it.startsWith(BLOCK_HEADING) }
        if (start < 0) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            return (lines + listOf("") + newLines + listOf("")).joinToString(nl)
        }
        val end =
            (start + 1 until lines.size).firstOrNull { lines[it].startsWith("# ") || lines[it].startsWith("## ") }
                ?: lines.size
        val old = lines.subList(start + 1, end).toList()
        ROGER_LINES.forEach { prefix ->
            val mine = old.firstOrNull { it.startsWith(prefix) && it.removePrefix(prefix).isNotBlank() }
            val i = newLines.indexOfFirst { it.startsWith(prefix) }
            if (mine != null && i >= 0) newLines[i] = mine
        }
        val owned = FOURSIGHT_PREFIXES + ROGER_LINES
        val extras =
            old.filter { line ->
                line.isNotBlank() && owned.none { line.startsWith(it) } && !line.startsWith("_FourSight")
            }
        val trailingBlank = old.takeLastWhile { it.isBlank() }
        val replacement =
            newLines + extras + trailingBlank.ifEmpty { if (end < lines.size) listOf("") else emptyList() }
        return (lines.subList(0, start) + replacement + lines.subList(end, lines.size)).joinToString(nl)
    }

    /** The lines FourSight writes in the block (besides Roger's and the footer). */
    private val FOURSIGHT_PREFIXES =
        listOf(
            "- Views (",
            "- Average watch time / % watched:",
            "- Saves per 1,000 / shares per 1,000:",
            "- Followers per 1,000:",
        )
}
