package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import io.github.zoot.englishreader.util.NetworkChecker
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException

/** 把 profile 与 transport 层的失败转换为安全的 [AiError] 契约。 */
class AiErrorMapper(
    private val isOnline: () -> Boolean,
    private val retryAfterParser: RetryAfterParser = RetryAfterParser()
) {
    @Inject
    constructor(networkChecker: NetworkChecker) : this(networkChecker::isOnline)

    fun mapProfileResolution(result: ProfileResolutionResult): AiError? = when (result) {
        is ProfileResolutionResult.Available -> null
        ProfileResolutionResult.Missing -> AiError.CredentialMissing
        ProfileResolutionResult.StorageUnavailable -> AiError.CredentialStorageUnavailable
        ProfileResolutionResult.NoActiveProfile -> AiError.NoActiveProfile
        ProfileResolutionResult.ProfileNotFound -> AiError.ProfileNotFound
        ProfileResolutionResult.InvalidEndpoint -> AiError.InvalidEndpoint
    }

    fun map(exception: Exception): AiError {
        if (exception is CancellationException) throw exception

        return when (exception) {
            is HttpException -> mapHttpException(exception)
            is AiTimeoutException -> AiError.Timeout(exception.phase)
            is SocketTimeoutException -> AiError.Timeout(TimeoutPhase.READ)
            is InterruptedIOException -> AiError.Timeout(TimeoutPhase.CALL)
            is UnknownHostException -> if (onlineSafely()) {
                AiError.DnsFailure
            } else {
                AiError.Offline
            }
            is SSLException -> AiError.TlsFailure
            is JsonEncodingException,
            is JsonDataException,
            is EOFException -> AiError.MalformedResponse
            is IOException -> if (onlineSafely()) AiError.Unknown else AiError.Offline
            else -> AiError.Unknown
        }
    }

    private fun mapHttpException(exception: HttpException): AiError {
        val status = exception.code()
        return when {
            status == 401 || status == 403 -> AiError.HttpAuth(status)
            status == 404 -> AiError.HttpNotFound()
            status == 408 -> AiError.RequestTimeout()
            status == 413 -> AiError.PayloadTooLarge()
            status == 429 -> AiError.RateLimited(
                retryAfterParser.parse(exception.response()?.headers()?.get("Retry-After"))
            )
            status in 500..599 -> AiError.Server(status)
            else -> AiError.UnexpectedHttp(status)
        }
    }

    private fun onlineSafely(): Boolean = try {
        isOnline()
    } catch (_: Exception) {
        true
    }
}

/** 内部标记类型，在 OkHttp 调用边界完成阶段区分后抛出。 */
internal class AiTimeoutException(
    val phase: TimeoutPhase,
    cause: IOException? = null
) : IOException("AI request timed out", cause)
