package io.github.zoot.englishreader.data.remote.ai

import io.github.zoot.englishreader.data.ai.TimeoutPhase
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * 对 OkHttp 超时异常做**保守**的阶段分类。
 *
 * 保守体现在两处：只有 [lastPhase] 已经指示处于 CONNECT 阶段时才报 CONNECT，否则
 * `SocketTimeoutException` 一律归为 READ——读超时是更常见也更安全的归因；无法识别的
 * `IOException` 返回 null 交给上层按通用网络错误处理，不硬猜阶段。
 */
object AiTimeoutClassifier {
    fun classify(exception: IOException, lastPhase: TimeoutPhase?): TimeoutPhase? {
        return when {
            exception is SocketTimeoutException && lastPhase == TimeoutPhase.CONNECT ->
                TimeoutPhase.CONNECT
            exception is SocketTimeoutException -> TimeoutPhase.READ
            exception is InterruptedIOException -> TimeoutPhase.CALL
            else -> null
        }
    }
}
