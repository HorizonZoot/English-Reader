package io.github.zoot.englishreader.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 进程级协程作用域限定符。
 *
 * 付费 AI 请求的生命周期必须长于任何 UI：关闭面板不等于服务端没生成、没计费，
 * 故请求由此作用域持有，而非 viewModelScope。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationCoroutineScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    /**
     * 全应用唯一的协程作用域。
     *
     * `SupervisorJob` 保证单个请求失败不连带取消同作用域内的其他请求。
     *
     * 注意：**消费方只能 launch 子协程，不得 cancel 这个作用域**——取消会从父 Job 向下
     * 传播，一次误调用就会杀掉所有在途付费请求。测试通过注入自己的 `TestScope` 来控制
     * 生命周期，而不是取消生产作用域。
     */
    @Provides
    @Singleton
    @ApplicationCoroutineScope
    fun provideApplicationCoroutineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
