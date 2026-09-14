package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.di.ApplicationCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 付费连接探测的 single-flight 边界，由 application 持有。
 *
 * 被取消的等待者只是停止等待。owner 会一直执行到自己的终态结果，并负责移除记录和对外的
 * in-flight 状态。
 */
class AiConnectionTestRegistry(
    @ApplicationCoroutineScope private val ownerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private data class Record(
        val result: CompletableDeferred<AiClientResult>
    )

    private val lock = Any()
    private val records = mutableMapOf<String, Record>()
    private val _inFlightProfileIds = MutableStateFlow<Set<String>>(emptySet())
    val inFlightProfileIds: StateFlow<Set<String>> = _inFlightProfileIds.asStateFlow()

    suspend fun run(
        profileId: String,
        operation: suspend () -> AiClientResult
    ): AiClientResult {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        val record = synchronized(lock) {
            records[profileId] ?: createOwnedRecordLocked(profileId, operation)
        }
        // await 可被取消，但不会取消由 application 持有的 CompletableDeferred。
        return record.result.await()
    }

    private fun createOwnedRecordLocked(
        profileId: String,
        operation: suspend () -> AiClientResult
    ): Record {
        val record = Record(CompletableDeferred())
        records[profileId] = record
        _inFlightProfileIds.value = records.keys.toSet()
        val owner = ownerScope.launch {
            try {
                completeOwned(profileId, record, operation())
            } catch (cancellation: CancellationException) {
                cancelOwned(profileId, record, cancellation)
            } catch (_: Exception) {
                completeOwned(
                    profileId,
                    record,
                    AiClientResult.Failure(AiError.Unknown)
                )
            }
        }
        owner.invokeOnCompletion { cause ->
            if (!record.result.isCompleted) {
                if (cause is CancellationException) {
                    cancelOwned(profileId, record, cause)
                } else {
                    completeOwned(
                        profileId,
                        record,
                        AiClientResult.Failure(AiError.Unknown)
                    )
                }
            } else {
                removeOwned(profileId, record)
            }
        }
        return record
    }

    private fun completeOwned(
        profileId: String,
        record: Record,
        result: AiClientResult
    ) {
        // 先发布「可再次发起」状态再唤醒等待者，这样 run() 返回后的重复调用是一次全新探测。
        removeOwned(profileId, record)
        record.result.complete(result)
    }

    private fun cancelOwned(
        profileId: String,
        record: Record,
        cancellation: CancellationException
    ) {
        removeOwned(profileId, record)
        record.result.cancel(cancellation)
    }

    private fun removeOwned(profileId: String, record: Record) {
        synchronized(lock) {
            if (records[profileId] === record) {
                records.remove(profileId)
                _inFlightProfileIds.value = records.keys.toSet()
            }
        }
    }
}
