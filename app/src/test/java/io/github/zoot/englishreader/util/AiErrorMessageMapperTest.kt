package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.TimeoutPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class AiErrorMessageMapperTest {

    @Test
    fun toUiMessage_everyTypedError_mapsToAResource() {
        val cases = listOf(
            AiError.NoActiveProfile to R.string.settings_ai_error_no_active_profile,
            AiError.ProfileNotFound to R.string.settings_ai_error_profile_not_found,
            AiError.CredentialMissing to R.string.settings_ai_error_credential_missing,
            AiError.CredentialStorageUnavailable to R.string.settings_ai_error_credential_storage,
            AiError.InvalidEndpoint to R.string.settings_ai_error_invalid_endpoint,
            AiError.InputTooLong(actualChars = 8_001, maxChars = 8_000) to
                R.string.ai_explanation_error_input_too_long,
            AiError.HttpAuth(401) to R.string.settings_ai_error_auth,
            AiError.HttpNotFound() to R.string.settings_ai_error_not_found,
            AiError.RequestTimeout() to R.string.settings_ai_error_http_timeout,
            AiError.PayloadTooLarge() to R.string.settings_ai_error_payload_too_large,
            AiError.RateLimited(12) to R.string.settings_ai_error_rate_limited,
            AiError.Server(503) to R.string.settings_ai_error_server,
            AiError.UnexpectedHttp(418) to R.string.settings_ai_error_unexpected_http,
            AiError.Timeout(TimeoutPhase.CALL) to R.string.settings_ai_error_timeout,
            AiError.Offline to R.string.settings_ai_error_offline,
            AiError.DnsFailure to R.string.settings_ai_error_dns,
            AiError.TlsFailure to R.string.settings_ai_error_tls,
            AiError.MalformedResponse to R.string.settings_ai_error_malformed,
            AiError.NoContent to R.string.settings_ai_error_no_content,
            AiError.Unknown to R.string.settings_ai_error_unknown
        )

        cases.forEach { (error, expectedResource) ->
            assertEquals("Unexpected resource for $error", expectedResource, error.toUiMessage().resourceId)
        }
    }

    @Test
    fun toUiMessage_inputTooLong_preservesOnlyBoundedCounts() {
        val message = AiError.InputTooLong(
            actualChars = 8_001,
            maxChars = 8_000
        ).toUiMessage()

        assertEquals(R.string.ai_explanation_error_input_too_long, message.resourceId)
        assertEquals(listOf(8_001, 8_000), message.formatArgs)
    }
}
