package io.github.zoot.englishreader.data.repository

import android.util.Log
import io.github.zoot.englishreader.BuildConfig
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.remote.update.GitHubReleaseApiService
import io.github.zoot.englishreader.data.update.AppVersion
import io.github.zoot.englishreader.util.NetworkChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val versionName: String,
        val releaseTitle: String?,
        val releaseNotes: String?,
        val releaseUrl: String
    ) : UpdateCheckResult

    data object UpToDate : UpdateCheckResult
    data object Skipped : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

@Singleton
class UpdateRepository internal constructor(
    private val apiService: GitHubReleaseApiService,
    private val settingsPreferences: SettingsPreferences,
    private val networkChecker: NetworkChecker,
    private val localVersionName: String,
    private val currentTimeMillis: () -> Long
) {
    @Inject
    constructor(
        apiService: GitHubReleaseApiService,
        settingsPreferences: SettingsPreferences,
        networkChecker: NetworkChecker
    ) : this(apiService, settingsPreferences, networkChecker, BuildConfig.VERSION_NAME, System::currentTimeMillis)

    private val launchCheckStarted = AtomicBoolean(false)
    private val checkMutex = Mutex()

    // An Activity can finish and restart in the same process, unlike a retained ViewModel.
    suspend fun checkOnLaunch(): UpdateCheckResult =
        if (launchCheckStarted.compareAndSet(false, true)) check(manual = false) else UpdateCheckResult.Skipped

    suspend fun check(manual: Boolean): UpdateCheckResult = checkMutex.withLock {
        try {
            if (!manual && isThrottled()) return@withLock UpdateCheckResult.Skipped
            if (!networkChecker.isOnline()) {
                return@withLock if (manual) UpdateCheckResult.Failed else UpdateCheckResult.Skipped
            }
            val releases = apiService.getPublishedReleases()
            // Moshi accepts a JSON null element despite the non-null declaration; it is not "no releases".
            val release = releases.firstOrNull()
            check(releases.isEmpty() || release != null) { "Null release element" }
            val result = if (release != null && AppVersion.isNewer(release.tagName, localVersionName)) {
                UpdateCheckResult.UpdateAvailable(release.tagName, release.name, release.body, release.htmlUrl)
            } else {
                UpdateCheckResult.UpToDate
            }
            markChecked()
            result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.javaClass.simpleName}")
            UpdateCheckResult.Failed
        }
    }

    private suspend fun isThrottled(): Boolean {
        val last = settingsPreferences.lastUpdateCheckAt.first()
        val now = currentTimeMillis()
        return last > 0L && now >= last && now - last < THROTTLE_MILLIS
    }

    private suspend fun markChecked() {
        try {
            settingsPreferences.setLastUpdateCheckAt(currentTimeMillis())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // The release remains usable; a failed timestamp write only forfeits throttling.
            Log.w(TAG, "Failed to record update check time: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "UpdateRepository"
        val THROTTLE_MILLIS = TimeUnit.HOURS.toMillis(24)
    }
}
