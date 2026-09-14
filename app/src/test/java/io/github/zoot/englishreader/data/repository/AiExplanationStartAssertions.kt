package io.github.zoot.englishreader.data.repository

import org.junit.Assert.assertTrue

/**
 * 断言启动结果是 [AiExplanationStartResult.Started] 并收窄类型。
 *
 * 只做类型收窄加原有断言，不附带任何业务语义——调用方仍要自己表达「为什么这次启动
 * 应该成功」。原先在 `AiExplanationRepositoryTest` 与 `AiExplanationCancellationRaceTest`
 * 各有一份逐字相同的私有实现。
 */
internal fun AiExplanationStartResult.requireStarted(): AiExplanationStartResult.Started {
    assertTrue(this is AiExplanationStartResult.Started)
    return this as AiExplanationStartResult.Started
}
