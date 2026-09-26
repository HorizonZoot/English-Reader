package io.github.zoot.englishreader.data.update

import java.io.File
import java.io.IOException

data class PendingUpdateDownload(val id: Long, val tag: String)

enum class UpdateDownloadFailure {
    NETWORK, STORAGE, INVALID_PACKAGE, WRONG_SIGNATURE, WRONG_VERSION, UNSUPPORTED_DEVICE, UNAVAILABLE
}

class UpdateDownloadException(val reason: UpdateDownloadFailure) : IOException(reason.name)

sealed interface UpdateDownloadState {
    val hasDownloadTarget: Boolean
        get() = this != Idle && (this !is Failed || UpdateReleasePolicy.isValidTag(tag))

    data object Idle : UpdateDownloadState
    data class Downloading(val tag: String, val bytes: Long, val totalBytes: Long, val paused: Boolean) : UpdateDownloadState
    data class Verifying(val tag: String) : UpdateDownloadState
    data class Ready(val tag: String, val file: File) : UpdateDownloadState
    data class Failed(val tag: String, val reason: UpdateDownloadFailure) : UpdateDownloadState
}

sealed interface PlatformUpdateDownload {
    data class Progress(val bytes: Long, val totalBytes: Long, val paused: Boolean) : PlatformUpdateDownload
    data object Complete : PlatformUpdateDownload
    data class Failed(val reason: UpdateDownloadFailure) : PlatformUpdateDownload
}

interface UpdateDownloadBackend {
    suspend fun enqueue(tag: String): Long
    suspend fun status(id: Long): PlatformUpdateDownload
    suspend fun preparedFile(record: PendingUpdateDownload): File?
    suspend fun prepare(record: PendingUpdateDownload): File
    suspend fun remove(record: PendingUpdateDownload)
}
