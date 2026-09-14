package io.github.zoot.englishreader.data.ai

/**
 * AI 操作的安全且有限的失败契约。
 *
 * 只有稳定的分类和有界的数值元数据会越过这层边界。原始异常、endpoint、凭据、headers 和响应体
 * 都留在 facade 之下。
 */
sealed interface AiError {
    data object NoActiveProfile : AiError
    data object ProfileNotFound : AiError
    data object CredentialMissing : AiError
    data object CredentialStorageUnavailable : AiError
    data object InvalidEndpoint : AiError
    data class InputTooLong(val actualChars: Int, val maxChars: Int) : AiError

    data class HttpAuth(val status: Int) : AiError
    data class HttpNotFound(val status: Int = 404) : AiError
    data class RequestTimeout(val status: Int = 408) : AiError
    data class PayloadTooLarge(val status: Int = 413) : AiError
    data class RateLimited(val retryAfterSeconds: Long?) : AiError
    data class Server(val status: Int) : AiError
    data class UnexpectedHttp(val status: Int) : AiError

    data class Timeout(val phase: TimeoutPhase) : AiError
    data object Offline : AiError
    data object DnsFailure : AiError
    data object TlsFailure : AiError
    data object MalformedResponse : AiError
    data object NoContent : AiError
    data object Unknown : AiError
}

enum class TimeoutPhase {
    CONNECT,
    READ,
    CALL
}

internal val AiError.categoryName: String
    get() = when (this) {
        AiError.NoActiveProfile -> "no_active_profile"
        AiError.ProfileNotFound -> "profile_not_found"
        AiError.CredentialMissing -> "credential_missing"
        AiError.CredentialStorageUnavailable -> "credential_storage_unavailable"
        AiError.InvalidEndpoint -> "invalid_endpoint"
        is AiError.InputTooLong -> "input_too_long"
        is AiError.HttpAuth -> "http_auth"
        is AiError.HttpNotFound -> "http_not_found"
        is AiError.RequestTimeout -> "http_request_timeout"
        is AiError.PayloadTooLarge -> "http_payload_too_large"
        is AiError.RateLimited -> "http_rate_limited"
        is AiError.Server -> "http_server"
        is AiError.UnexpectedHttp -> "http_unexpected"
        is AiError.Timeout -> "timeout_${phase.name.lowercase()}"
        AiError.Offline -> "offline"
        AiError.DnsFailure -> "dns_failure"
        AiError.TlsFailure -> "tls_failure"
        AiError.MalformedResponse -> "malformed_response"
        AiError.NoContent -> "no_content"
        AiError.Unknown -> "unknown"
    }
