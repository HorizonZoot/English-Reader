package io.github.zoot.englishreader.data.ai

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 保留最外层的明确语义（如整次调用超时），取消优先于所有失败分类。 */
internal fun Throwable.aiFailureCause(): Throwable {
    val causes = ArrayList<Throwable>()
    var current: Throwable? = this
    while (current != null && causes.size < 32 && causes.none { it === current }) {
        causes += current
        current = current.cause
    }
    return causes.firstOrNull { it is CancellationException }
        ?: causes.firstOrNull {
            it is HttpException || it is AiTimeoutException || it is InterruptedIOException ||
                it is UnknownHostException || it is SSLException || it is JsonDataException ||
                it is JsonEncodingException || it is EOFException
        }
        ?: causes.firstOrNull { it is IOException }
        ?: this
}
