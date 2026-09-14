package io.github.zoot.englishreader.data.ai

/** 设置页连接测试动作的一次性结果。 */
data class AiConnectionTestEvent(
    val requestId: String,
    val profileId: String,
    val result: AiClientResult
) {
    override fun toString(): String = "AiConnectionTestEvent"
}
