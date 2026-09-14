package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.repository.AiExplanationOperationRegistry
import io.github.zoot.englishreader.model.AiOperationHandle
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.github.zoot.englishreader.model.AiOperationRef
import io.github.zoot.englishreader.model.AiSheetAttachment
import io.github.zoot.englishreader.model.AiSheetRequestToken
import io.github.zoot.englishreader.model.AiSheetState
import io.github.zoot.englishreader.model.AiExplanationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 只持有 UI 附着 token 和观察者；付费请求在 repository 边界以下才发起。 */
class AiSheetCoordinator(
    private val registry: AiExplanationOperationRegistry,
    private val observerScope: CoroutineScope
) {
    private val mutex = Mutex()
    private var generation = 0L
    private var currentToken: AiSheetRequestToken? = null
    private var observerJob: Job? = null

    private val _state = MutableStateFlow<AiSheetState>(AiSheetState.Hidden)
    val state: StateFlow<AiSheetState> = _state.asStateFlow()

    /** 在 profile 解析与请求准备之前先发布加载态，并作废所有更早的 token。 */
    suspend fun begin(target: AiExplanationTarget): AiSheetRequestToken = mutex.withLock {
        observerJob?.cancel()
        observerJob = null
        val token = AiSheetRequestToken(++generation)
        currentToken = token
        _state.value = AiSheetState.Loading(token, target)
        token
    }

    /** 仅当 [token] 仍然指向最新那次请求时，才附着这个不透明 handle。 */
    suspend fun attach(token: AiSheetRequestToken, handle: AiOperationHandle): Boolean =
        mutex.withLock {
            val loading = _state.value as? AiSheetState.Loading
            if (currentToken != token || loading?.requestToken != token) return@withLock false

            observerJob?.cancel()
            val attachment = AiSheetAttachment(
                generation = token.generation,
                operationRef = handle.ref
            )
            _state.value = AiSheetState.Visible(
                attachment = attachment,
                target = loading.target
            )
            observerJob = observerScope.launch {
                val outcome = handle.awaitOutcome()
                mutex.withLock observerLock@{
                    val current = _state.value
                    if (currentToken != token ||
                        current !is AiSheetState.Visible ||
                        current.attachment.operationRef != handle.ref
                    ) {
                        return@observerLock
                    }

                    observerJob = null
                    _state.value = when (outcome) {
                        AiOperationOutcome.Cancelled -> {
                            currentToken = null
                            AiSheetState.Hidden
                        }
                        else -> {
                            currentToken = null
                            current.copy(outcome = outcome)
                        }
                    }
                }
            }
            true
        }

    /** 仅当本次准备仍归属于 [token] 时，才落定本地或 profile 侧的拒绝结果。 */
    suspend fun reject(token: AiSheetRequestToken, error: AiError): Boolean = mutex.withLock {
        val loading = _state.value as? AiSheetState.Loading
        if (currentToken != token || loading?.requestToken != token) return@withLock false
        observerJob?.cancel()
        observerJob = null
        currentToken = null
        _state.value = AiSheetState.Rejected(error = error, target = loading.target)
        true
    }

    /** 当准备阶段在附着之前就被取消时，作废这个加载态 token。 */
    suspend fun invalidate(token: AiSheetRequestToken): Boolean = mutex.withLock {
        val loading = _state.value as? AiSheetState.Loading
        if (currentToken != token || loading?.requestToken != token) return@withLock false
        observerJob?.cancel()
        observerJob = null
        currentToken = null
        _state.value = AiSheetState.Hidden
        true
    }

    /** 解除观察者并作废在途的准备工作，但不取消已发出的付费请求。 */
    suspend fun dismiss() {
        mutex.withLock {
            observerJob?.cancel()
            observerJob = null
            currentToken = null
            _state.value = AiSheetState.Hidden
        }
    }

    /** 精确取消已注册的那个操作；ref 已完成或已过期时不做任何事。 */
    suspend fun cancel(ref: AiOperationRef): Boolean {
        val cancelled = registry.cancel(ref)
        if (!cancelled) return false

        mutex.withLock {
            val current = _state.value
            if (current is AiSheetState.Visible && current.attachment.operationRef == ref) {
                observerJob?.cancel()
                observerJob = null
                currentToken = null
                _state.value = AiSheetState.Hidden
            }
        }
        return true
    }
}
