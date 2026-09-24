package io.github.zoot.englishreader.data.remote.ai

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url
import retrofit2.Response

/**
 * OpenAI 兼容的 chat completions 接口。
 *
 * 用 `@Url` 传绝对地址而非 Retrofit 固定 `baseUrl()`，原因有两条且相互独立：
 *
 * 1. `Retrofit.Builder.baseUrl()` **拒绝**不以 `/` 结尾的值，抛
 *    `IllegalArgumentException("baseUrl must end in /")`。三个预设里 DeepSeek 与 Kimi
 *    都不带尾斜杠，会在构造期直接失败。
 * 2. endpoint 是逐 profile 的运行时值，固定 base URL 意味着每个 profile 重建 Retrofit，
 *    对无状态 transport 是错误的形状。
 *
 * 认证头逐次传入而非走共享 interceptor：key 属于 profile，共享 interceptor 需要可变的
 * 逐请求状态。
 */
interface AiChatCompletionApi {

    @GET
    suspend fun listModels(
        @Url url: String,
        @Header("Authorization") authorization: String
    ): Response<AiModelListResponse>

    @POST
    suspend fun createChatCompletion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: AiChatCompletionRequest
    ): Response<AiChatCompletionResponse>
}
