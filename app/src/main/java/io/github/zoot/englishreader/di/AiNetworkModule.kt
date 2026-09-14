package io.github.zoot.englishreader.di

import io.github.zoot.englishreader.data.ai.AiClient
import io.github.zoot.englishreader.data.ai.AiConnectionTestRegistry
import io.github.zoot.englishreader.data.ai.AiErrorMapper
import io.github.zoot.englishreader.data.ai.AiExecutor
import io.github.zoot.englishreader.data.ai.AiExplanationRequestResolver
import io.github.zoot.englishreader.data.ai.CachedAiExecutor
import io.github.zoot.englishreader.data.ai.DefaultAiClient
import io.github.zoot.englishreader.data.ai.RemoteAiExecutor
import io.github.zoot.englishreader.data.remote.ai.AiChatCompletionApi
import io.github.zoot.englishreader.data.remote.ai.AiPhaseTrackingCallFactory
import io.github.zoot.englishreader.data.remote.ai.AiTimeoutEventListenerFactory
import io.github.zoot.englishreader.data.remote.ai.RetrofitAiChatTransport
import io.github.zoot.englishreader.data.remote.ai.AiChatTransport
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ExplanationCacheRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AiMoshi

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AiRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AiOkHttp

/**
 * AI 网络栈策略。四个超时与三个开关都与「付费、非流式」这一前提直接相关。
 *
 * 超时分工：`readTimeout` 放到 60s 是因为模型首个 token 的等待期算在读上，非流式请求要等
 * 完整响应；`connectTimeout`/`writeTimeout` 保持 15s，握手与发请求体不该慢。`callTimeout`
 * 90s 是整次调用的硬上限，防止某一阶段反复续期导致单次请求无限期挂住。
 */
internal object AiNetworkPolicy {
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 60L
    const val WRITE_TIMEOUT_SECONDS = 15L
    const val CALL_TIMEOUT_SECONDS = 90L

    /**
     * 三个 `false` 都是刻意的，不是保守默认：
     *
     * - `retryOnConnectionFailure(false)`：OkHttp 的自动重试对**付费**接口是错的。请求可能
     *   已被服务端处理并计费，重试会造成重复扣费，而调用方看到的仍是一次请求。重试策略应由
     *   上层按错误类别决定（见 `AiErrorMapper` 与 `RetryAfterParser`），不能藏在传输层。
     * - `followRedirects(false)` / `followSslRedirects(false)`：请求带 `Authorization` 头，
     *   跟随重定向会把凭据发往一个用户未曾配置的主机。endpoint 由 `AiEndpointResolver` 校验过，
     *   重定向等于绕过那道校验，因此宁可让重定向表现为错误。
     *
     * 全栈**不注册任何 Interceptor**：AI 路径禁止 `HttpLoggingInterceptor`，而空的拦截器链
     * 让这条禁令没有可被绕过的入口。
     */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
}

@Module
@InstallIn(SingletonComponent::class)
object AiNetworkModule {
    private const val RETROFIT_BASE_URL = "https://ai.invalid/"

    @Provides
    @Singleton
    @AiMoshi
    fun provideAiMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    @AiOkHttp
    fun provideAiOkHttp(): OkHttpClient = AiNetworkPolicy.applyTo(OkHttpClient.Builder())
        .eventListenerFactory(AiTimeoutEventListenerFactory())
        .build()

    @Provides
    @Singleton
    @AiRetrofit
    fun provideAiRetrofit(
        @AiOkHttp okHttpClient: OkHttpClient,
        @AiMoshi moshi: Moshi
    ): Retrofit = Retrofit.Builder()
        .baseUrl(RETROFIT_BASE_URL)
        .callFactory(AiPhaseTrackingCallFactory(okHttpClient))
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun provideAiChatCompletionApi(@AiRetrofit retrofit: Retrofit): AiChatCompletionApi =
        retrofit.create(AiChatCompletionApi::class.java)

    @Provides
    @Singleton
    fun provideAiChatTransport(api: AiChatCompletionApi): AiChatTransport =
        RetrofitAiChatTransport(api)

    @Provides
    @Singleton
    internal fun provideRemoteAiExecutor(
        transport: AiChatTransport,
        errorMapper: AiErrorMapper
    ): RemoteAiExecutor = RemoteAiExecutor(transport, errorMapper)

    @Provides
    @Singleton
    internal fun provideCachedAiExecutor(
        remoteExecutor: RemoteAiExecutor,
        cacheRepository: ExplanationCacheRepository
    ): AiExecutor = CachedAiExecutor(remoteExecutor, cacheRepository)

    @Provides
    @Singleton
    fun provideAiConnectionTestRegistry(
        @ApplicationCoroutineScope ownerScope: CoroutineScope
    ): AiConnectionTestRegistry = AiConnectionTestRegistry(ownerScope)

    @Provides
    @Singleton
    internal fun provideAiClient(
        profileRepository: AiProfileRepository,
        requestResolver: AiExplanationRequestResolver,
        cachedExecutor: AiExecutor,
        remoteExecutor: RemoteAiExecutor,
        connectionRegistry: AiConnectionTestRegistry,
        errorMapper: AiErrorMapper
    ): AiClient = DefaultAiClient(
        profileRepository = profileRepository,
        requestResolver = requestResolver,
        executor = cachedExecutor,
        connectionExecutor = remoteExecutor,
        connectionRegistry = connectionRegistry,
        errorMapper = errorMapper
    )
}
