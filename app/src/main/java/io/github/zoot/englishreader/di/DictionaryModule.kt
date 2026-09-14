package io.github.zoot.englishreader.di

import io.github.zoot.englishreader.data.remote.dictionary.DictionaryApiService
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
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DictionaryRetrofit

/**
 * Dictionary API 网络模块。
 *
 * baseUrl 在构建时固定。曾有一个 DictionaryPreferences 存储用户自定义词典源，但 Retrofit
 * 从未消费它——注释宣称的「动态词典源」是不存在的能力，已连同该类一并删除。真要支持切换
 * 词源时，应实现 OkHttp Interceptor 或重建 Retrofit，而不是留一个只写不读的配置项。
 */
@Module
@InstallIn(SingletonComponent::class)
object DictionaryModule {

    /** Free Dictionary API。注意：该服务在中国大陆不可访问，离线词典失败后的降级会超时。 */
    private const val DICTIONARY_BASE_URL = "https://api.dictionaryapi.dev/api/v2/entries/en/"

    @Provides
    @Singleton
    @DictionaryRetrofit
    fun provideDictionaryOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @DictionaryRetrofit
    fun provideDictionaryRetrofit(
        @DictionaryRetrofit okHttpClient: OkHttpClient,
        @DictionaryRetrofit moshi: Moshi
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(DICTIONARY_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    @DictionaryRetrofit
    fun provideDictionaryMoshi(): Moshi {
        return Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    @Provides
    @Singleton
    fun provideDictionaryApiService(
        @DictionaryRetrofit retrofit: Retrofit
    ): DictionaryApiService {
        return retrofit.create(DictionaryApiService::class.java)
    }
}
