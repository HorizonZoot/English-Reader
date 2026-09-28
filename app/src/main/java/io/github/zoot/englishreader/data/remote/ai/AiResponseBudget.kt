package io.github.zoot.englishreader.data.remote.ai

import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/** Limits apply after OkHttp decompression and before Retrofit buffers or decodes the body. */
internal object AiResponseBudget {
    const val CHAT_BYTES = 1L * 1024 * 1024
    const val MODEL_CATALOG_BYTES = 2L * 1024 * 1024

    fun read(response: Response, call: Call): Response {
        val body = response.body
        if (response.code != 200) {
            // Status and Retry-After are sufficient; never drain or retain provider error text.
            call.cancel()
            body?.close()
            throw AiHttpStatusException(response.code, response.header("Retry-After"))
        }
        if (body == null) return response
        val limit = if (response.request.method == "GET") MODEL_CATALOG_BYTES else CHAT_BYTES
        return body.use {
            if (body.contentLength() > limit) {
                call.cancel()
                throw AiResponseTooLargeException()
            }
            val source = body.source()
            if (source.request(limit + 1)) {
                call.cancel()
                throw AiResponseTooLargeException()
            }
            response.newBuilder()
                .body(source.readByteString().toResponseBody(body.contentType()))
                .build()
        }
    }
}

internal class AiResponseTooLargeException : IOException("AI response exceeds byte budget")

internal class AiHttpStatusException(
    val status: Int,
    val retryAfter: String?
) : IOException("AI HTTP status $status")
