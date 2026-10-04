package io.github.zoot.englishreader.util

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import java.io.IOException

object SavedVocabularyMeaning {
    private val adapter = Moshi.Builder().build().adapter(Any::class.java)

    fun parse(stored: String?): String? {
        val text = stored?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!text.startsWith("[") && !text.startsWith("{")) return text
        return try {
            definitions(adapter.fromJson(text), 0).distinct().joinToString("\n").takeIf(String::isNotBlank)
        } catch (_: JsonDataException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun definitions(value: Any?, depth: Int): List<String> {
        if (depth > 5) return emptyList()
        return when (value) {
            is String -> listOfNotNull(value.trim().takeIf(String::isNotEmpty))
            is List<*> -> value.flatMap { definitions(it, depth + 1) }
            is Map<*, *> -> {
                val direct = value["definition"] ?: value["meaning"] ?: value["translation"]
                val nested = value["definitions"] ?: value["meanings"]
                listOfNotNull(direct, nested).flatMap { definitions(it, depth + 1) }
            }
            else -> emptyList()
        }
    }
}
