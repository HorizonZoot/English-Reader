package io.github.zoot.englishreader.data.ai

/**
 * 执行一次已解析的 AI 解释请求。
 *
 * 这是 6.4 新增的内部 seam，只有 data 层可见（ViewModel 不可见）。
 * [CachedAiExecutor] 和 [RemoteAiExecutor] 都实现本接口，前者包装后者，
 * 共享同一个 [ResolvedAiExplanationRequest] 快照，确保缓存身份与真实请求
 * 使用相同的 endpoint / model / 有效参数 / 规范化输入。
 *
 * 公开契约 [AiClient] 负责输入策略；[DefaultAiClient] 内部负责解析 profile、
 * 规范化并构造完整 request 快照，然后委托给本接口的实现。
 */
internal interface AiExecutor {
    suspend fun execute(
        operation: ResolvedAiExplanationOperation,
        completionGate: AiOperationCompletionGate
    ): AiClientResult
}
