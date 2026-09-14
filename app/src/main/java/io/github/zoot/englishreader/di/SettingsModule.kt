package io.github.zoot.englishreader.di

import android.content.Context
import io.github.zoot.englishreader.data.local.AiCredentialStorage
import io.github.zoot.englishreader.data.local.AiProfileMetadataStore
import io.github.zoot.englishreader.data.local.EncryptedAiCredentialStorage
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideSettingsPreferences(
        @ApplicationContext context: Context
    ): SettingsPreferences = SettingsPreferences(context)

    @Provides
    @Singleton
    fun provideAiCredentialStorage(
        @ApplicationContext context: Context
    ): AiCredentialStorage = EncryptedAiCredentialStorage(context)

    @Provides
    @Singleton
    fun provideAiProfileMetadataStore(
        @ApplicationContext context: Context,
        moshi: Moshi
    ): AiProfileMetadataStore = AiProfileMetadataStore(context, moshi)

    @Provides
    @Singleton
    fun provideAiProfileRepository(
        metadataStore: AiProfileMetadataStore,
        credentialStorage: AiCredentialStorage
    ): AiProfileRepository = AiProfileRepository(metadataStore, credentialStorage)

    @Provides
    @Singleton
    fun provideSettingsMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
}
