package com.karelisio.speech.access

/**
 * Résultat de l'insertion d'une dictée dans le texte existant d'un champ.
 * [piece] est le texte réellement inséré (espaces compris), à partir de [start].
 */
data class Merged(val text: String, val cursor: Int, val start: Int, val piece: String)

object TextMerge {
    private val NO_SPACE_BEFORE = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '…', '%')

    /**
     * Insère [insert] à la place de la sélection [selStart, selEnd) (ou à la fin si la sélection
     * est inconnue) sans toucher au reste, en ajoutant les espaces nécessaires autour.
     */
    fun merge(existing: String, selStart: Int, selEnd: Int, insert: String): Merged {
        val text = insert.trim()
        val len = existing.length
        val (s, e) = if (selStart < 0 || selEnd < 0 || selStart > len || selEnd > len) {
            len to len
        } else {
            minOf(selStart, selEnd) to maxOf(selStart, selEnd)
        }
        val before = existing.substring(0, s)
        val after = existing.substring(e)
        if (text.isEmpty()) return Merged(existing, e, s, "")

        val needSpaceBefore = before.isNotEmpty() && !before.last().isWhitespace() && text.first() !in NO_SPACE_BEFORE
        val needSpaceAfter = after.isNotEmpty() && (after.first().isLetterOrDigit())
        val piece = (if (needSpaceBefore) " " else "") + text + (if (needSpaceAfter) " " else "")
        val head = before + piece
        return Merged(head + after, head.length, s, piece)
    }
}
