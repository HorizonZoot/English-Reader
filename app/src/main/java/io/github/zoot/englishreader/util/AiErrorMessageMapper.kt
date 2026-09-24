package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.ai.AiError

/** 有限 AI 错误契约到 UI 的稳定映射；原始异常绝不会到达 Compose。 */
data class AiErrorMessage(
    val resourceId: Int,
    val formatArgs: List<Any> = emptyList()
)

fun AiError.toUiMessage(): AiErrorMessage = when (this) {
    AiError.NoActiveProfile -> AiErrorMessage(R.string.settings_ai_error_no_active_profile)
    AiError.ProfileNotFound -> AiErrorMessage(R.string.settings_ai_error_profile_not_found)
    AiError.CredentialMissing -> AiErrorMessage(R.string.settings_ai_error_credential_missing)
    AiError.CredentialStorageUnavailable ->
        AiErrorMessage(R.string.settings_ai_error_credential_storage)
    AiError.InvalidEndpoint -> AiErrorMessage(R.string.settings_ai_error_invalid_endpoint)
    AiError.ModelUnavailable -> AiErrorMessage(R.string.ai_error_model_unavailable)
    is AiError.InputTooLong -> AiErrorMessage(
        resourceId = R.string.ai_explanation_error_input_too_long,
        formatArgs = listOf(actualChars, maxChars)
    )
    is AiError.HttpAuth -> AiErrorMessage(R.string.settings_ai_error_auth)
    is AiError.HttpNotFound -> AiErrorMessage(R.string.settings_ai_error_not_found)
    is AiError.RequestTimeout -> AiErrorMessage(R.string.settings_ai_error_http_timeout)
    is AiError.PayloadTooLarge -> AiErrorMessage(R.string.settings_ai_error_payload_too_large)
    is AiError.RateLimited -> AiErrorMessage(R.string.settings_ai_error_rate_limited)
    is AiError.Server -> AiErrorMessage(R.string.settings_ai_error_server)
    is AiError.UnexpectedHttp -> AiErrorMessage(R.string.settings_ai_error_unexpected_http)
    is AiError.Timeout -> AiErrorMessage(R.string.settings_ai_error_timeout)
    AiError.Offline -> AiErrorMessage(R.string.settings_ai_error_offline)
    AiError.DnsFailure -> AiErrorMessage(R.string.settings_ai_error_dns)
    AiError.TlsFailure -> AiErrorMessage(R.string.settings_ai_error_tls)
    AiError.MalformedResponse -> AiErrorMessage(R.string.settings_ai_error_malformed)
    AiError.ResponseTruncated -> AiErrorMessage(R.string.settings_ai_error_truncated)
    AiError.NoContent -> AiErrorMessage(R.string.settings_ai_error_no_content)
    AiError.Unknown -> AiErrorMessage(R.string.settings_ai_error_unknown)
}
