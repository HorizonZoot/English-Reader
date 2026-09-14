package io.github.zoot.englishreader.data.remote.dictionary

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Free Dictionary API 响应模型
 */
@JsonClass(generateAdapter = true)
data class DictionaryResponse(
    @Json(name = "word")
    val word: String,

    @Json(name = "phonetic")
    val phonetic: String? = null,

    @Json(name = "phonetics")
    val phonetics: List<Phonetic>? = null,

    @Json(name = "meanings")
    val meanings: List<Meaning>
)

@JsonClass(generateAdapter = true)
data class Phonetic(
    @Json(name = "text")
    val text: String? = null,

    @Json(name = "audio")
    val audio: String? = null
)

@JsonClass(generateAdapter = true)
data class Meaning(
    @Json(name = "partOfSpeech")
    val partOfSpeech: String,

    @Json(name = "definitions")
    val definitions: List<Definition>
)

@JsonClass(generateAdapter = true)
data class Definition(
    @Json(name = "definition")
    val definition: String,

    @Json(name = "example")
    val example: String? = null,

    @Json(name = "synonyms")
    val synonyms: List<String>? = null
)
