package io.github.zoot.englishreader.util

import java.util.Locale

/** Lookup identity only; never replace source text used for display or offsets. */
object WordLookupKey {
    fun normalize(word: String): String = foldApostrophes(word.trim()).lowercase(Locale.ROOT)

    /** One UTF-16 code unit for one, so searching this copy preserves source offsets. */
    fun foldApostrophes(text: String): String = text.replace('\u2019', '\'')
}
