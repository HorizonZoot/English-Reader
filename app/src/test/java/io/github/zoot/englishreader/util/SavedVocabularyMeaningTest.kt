package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.model.StudyWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedVocabularyMeaningTest {
    @Test
    fun parse_savedFormats_returnsOnlyDefinitions() {
        assertEquals("河岸", SavedVocabularyMeaning.parse("  河岸  "))
        assertEquals("bank\n银行", SavedVocabularyMeaning.parse("""["bank", "银行", "bank"]"""))
        assertEquals("shore\n银行", SavedVocabularyMeaning.parse("""
            [{"partOfSpeech":"noun", "definitions":[{"definition":"shore", "example":"not an answer"}]},
             {"meanings":[{"translation":"银行"}]}]
        """.trimIndent()))
    }

    @Test
    fun parse_absentMalformedOrMetadataOnly_doesNotInventAnAnswer() {
        listOf(null, "", "  ", "[broken", "{}", "[]", "{\"example\":\"not an answer\"}")
            .forEach { assertNull(it, SavedVocabularyMeaning.parse(it)) }
    }

    @Test
    fun legacy_editedMeaningOverridesSavedDefinition_andIdentityIncludesSource() {
        val vocabulary = VocabularyEntity(id = 1, word = "bank", definitions = "old", studyMeaning = "edited", createdAt = 1)
        assertEquals("edited", StudyWord.Legacy(vocabulary).meaning)
        assertEquals("old", StudyWord.Legacy(vocabulary.copy(studyMeaning = null)).meaning)
        assertNotEquals(StudyWord.Context(contextCard(1)).key, StudyWord.Legacy(vocabulary).key)
    }
}
