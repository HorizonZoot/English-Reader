package io.github.zoot.englishreader.di

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.zoot.englishreader.data.remote.update.GitHubReleaseApiService
import io.github.zoot.englishreader.data.remote.update.GitHubReleaseFeedApiService
import io.github.zoot.englishreader.data.update.GitHubUpdateReleaseSource
import io.github.zoot.englishreader.data.update.AndroidUpdateDownloads
import io.github.zoot.englishreader.data.update.UpdateDownloadBackend
import io.github.zoot.englishreader.data.update.UpdateReleaseSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GitHubRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateIo

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    private const val GITHUB_API_BASE_URL = "https://api.github.com/"

    @Provides
    @UpdateIo
    fun provideUpdateDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideUpdateDownloads(backend: AndroidUpdateDownloads): UpdateDownloadBackend = backend

    @Provides
    @Singleton
    fun provideReleaseSource(source: GitHubUpdateReleaseSource): UpdateReleaseSource = source

    @Provides
    @Singleton
    fun provideReleaseFeedService(@GitHubRetrofit client: OkHttpClient): GitHubReleaseFeedApiService =
        Retrofit.Builder()
            .baseUrl("https://github.com/")
            .client(client)
            .build()
            .create(GitHubReleaseFeedApiService::class.java)

    @Provides
    @Singleton
    @GitHubRetrofit
    fun provideGitHubOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @GitHubRetrofit
    fun provideGitHubMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    @GitHubRetrofit
    fun provideGitHubRetrofit(
        @GitHubRetrofit okHttpClient: OkHttpClient,
        @GitHubRetrofit moshi: Moshi
    ): Retrofit = Retrofit.Builder()
        .baseUrl(GITHUB_API_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun provideGitHubReleaseApiService(
        @GitHubRetrofit retrofit: Retrofit
    ): GitHubReleaseApiService = retrofit.create(GitHubReleaseApiService::class.java)
}
