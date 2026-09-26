package io.github.zoot.englishreader.data.update

import androidx.core.text.HtmlCompat
import io.github.zoot.englishreader.data.remote.update.GitHubRelease
import io.github.zoot.englishreader.data.remote.update.GitHubReleaseApiService
import io.github.zoot.englishreader.data.remote.update.GitHubReleaseFeedApiService
import io.github.zoot.englishreader.di.UpdateIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

fun interface UpdateReleaseSource {
    suspend fun getPublishedReleases(): List<GitHubRelease>
}

class UpdateRateLimitedException(val retryAtMillis: Long) : IOException("Update source rate limited")

@Singleton
class GitHubUpdateReleaseSource internal constructor(
    private val feedService: GitHubReleaseFeedApiService,
    private val apiService: GitHubReleaseApiService,
    private val ioDispatcher: CoroutineDispatcher,
    private val currentTimeMillis: () -> Long
) : UpdateReleaseSource {
    @Inject
    constructor(
        feedService: GitHubReleaseFeedApiService,
        apiService: GitHubReleaseApiService,
        @UpdateIo ioDispatcher: CoroutineDispatcher
    ) : this(feedService, apiService, ioDispatcher, System::currentTimeMillis)

    @Volatile
    private var apiRetryAtMillis = 0L

    override suspend fun getPublishedReleases(): List<GitHubRelease> {
        try {
            val entries = withContext(ioDispatcher) {
                feedService.getReleaseFeed().use { body ->
                    if (body.contentLength() > GitHubReleaseFeedParser.MAX_FEED_BYTES) {
                        throw IOException("Release feed too large")
                    }
                    val source = body.source()
                    source.request(GitHubReleaseFeedParser.MAX_FEED_BYTES + 1)
                    currentCoroutineContext().ensureActive()
                    if (source.buffer.size > GitHubReleaseFeedParser.MAX_FEED_BYTES) {
                        throw IOException("Release feed too large")
                    }
                    GitHubReleaseFeedParser.parse(source.readUtf8()).map { entry ->
                        GitHubRelease(
                            entry.tag,
                            entry.title,
                            entry.htmlContent?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() },
                            entry.url
                        )
                    }
                }
            }
            return entries.sortedWith { a, b ->
                AppVersion.parse(b.tagName)!!.compareTo(AppVersion.parse(a.tagName)!!)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
        }

        if (currentTimeMillis() < apiRetryAtMillis) throw UpdateRateLimitedException(apiRetryAtMillis)
        try {
            return apiService.getPublishedReleases()
        } catch (error: HttpException) {
            val headers = error.response()?.headers()
            val limited = error.code() == 429 || error.code() == 403 &&
                (headers?.get("X-RateLimit-Remaining") == "0" || headers?.get("Retry-After") != null)
            if (!limited) throw error
            val now = currentTimeMillis()
            val reset = headers?.get("X-RateLimit-Reset")?.toLongOrNull()
                ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
            val retrySeconds = headers?.get("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400) ?: 60
            apiRetryAtMillis = (reset ?: (now + retrySeconds * 1000)).coerceIn(now + 1000, now + 86_400_000)
            throw UpdateRateLimitedException(apiRetryAtMillis)
        }
    }
}
