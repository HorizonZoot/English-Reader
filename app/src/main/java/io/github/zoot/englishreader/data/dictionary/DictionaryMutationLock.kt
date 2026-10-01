package io.github.zoot.englishreader.data.dictionary

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/** Lock order: feature operation lock, dictionary mutation lock, then Room transaction. */
@Singleton
class DictionaryMutationLock @Inject constructor() {
    internal val mutex = Mutex()

    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    /**
     * Caller holds [mutex]. The block returns whether its completed transaction changed content.
     * Shield the transaction return/publication gap; long streaming writes must check the supplied
     * caller context inside the transaction so cancellation before commit still rolls back.
     */
    internal suspend fun commitMutation(block: suspend (CoroutineContext) -> Boolean) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        withContext(NonCancellable) {
            if (block(caller)) _revision.update { it + 1 }
        }
        caller.ensureActive()
    }
}
