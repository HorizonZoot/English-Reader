package io.github.zoot.englishreader.data.dictionary

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Lock order: feature operation lock, dictionary mutation lock, then Room transaction. */
@Singleton
class DictionaryMutationLock @Inject constructor() {
    internal val mutex = Mutex()
}
