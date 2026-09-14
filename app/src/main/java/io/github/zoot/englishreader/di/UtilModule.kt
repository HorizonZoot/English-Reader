package io.github.zoot.englishreader.di

import io.github.zoot.englishreader.util.AndroidNetworkChecker
import io.github.zoot.englishreader.util.NetworkChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 工具类 DI 绑定。
 *
 * [NetworkChecker] 是接口（为 ViewModel 单测可注入 mock），需 @Binds 绑到实现
 * [AndroidNetworkChecker]。TtsPlayer/AudioPlayer 均为具体类 + @Inject constructor，
 * Hilt 可自动构造，无需在此声明。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UtilModule {

    @Binds
    @Singleton
    abstract fun bindNetworkChecker(impl: AndroidNetworkChecker): NetworkChecker
}
