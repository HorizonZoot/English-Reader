package io.github.zoot.englishreader.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProfileValidationTest {

    @Test
    fun validate_blankInputs_reportAllRequiredFields() {
        val result = AiProfileValidation.validate(" ", " ", " ", " ", " ", false)
        assertEquals(AiProfileValidation(true, true, true, true, true), result)
        assertFalse(result.valid)
    }

    @Test
    fun validate_unsafeEndpoints_areRejected() {
        for (endpoint in listOf(
            "http://example.com", "https://user:secret@example.com",
            "https://example.com?key=secret", "https://example.com#fragment", "not a URL"
        )) {
            assertTrue(validInput(baseUrl = endpoint).endpointInvalid)
        }
        assertTrue(validInput(baseUrl = " https://example.com:8443/proxy/v1/ ").valid)
    }

    @Test
    fun validate_temperature_requiresFiniteNumberWithinInclusiveRange() {
        for (temperature in listOf("", "NaN", "Infinity", "-Infinity", "-0.01", "2.01", "abc")) {
            assertTrue(temperature, validInput(temperature = temperature).temperatureInvalid)
        }
        for (temperature in listOf("0", " 0.5 ", "2.0")) {
            assertTrue(temperature, validInput(temperature = temperature).valid)
        }
    }

    @Test
    fun validate_blankKey_isAllowedOnlyWhenSavedIdentityCanBeKept() {
        assertTrue(validInput(apiKey = " ").keyRequired)
        assertTrue(validInput(apiKey = " ", canKeepSavedKey = true).valid)
    }

    private fun validInput(
        baseUrl: String = "https://example.com/v1",
        temperature: String = "0.2",
        apiKey: String = "test-key",
        canKeepSavedKey: Boolean = false
    ) = AiProfileValidation.validate("Provider", baseUrl, "model-id", apiKey, temperature, canKeepSavedKey)
}
