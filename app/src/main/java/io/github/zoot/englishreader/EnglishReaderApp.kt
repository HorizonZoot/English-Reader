package io.github.zoot.englishreader

import android.app.Application
import android.util.Log
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.di.ApplicationCoroutineScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class EnglishReaderApp : Application() {

    @Inject
    lateinit var dictionaryRepository: DictionaryRepository

    /**
     * 注入的进程级作用域（见 [ApplicationCoroutineScope]）。
     *
     * 这里刻意**不持有取消权**。曾有一个私有 scope 配 `onTerminate()` 里的 `cancel()`，
     * 注释写着「仅用于测试」；作用域改为共享后那行必须删除——取消会沿父 Job 向下传播，
     * 一次 `onTerminate()` 就会连带杀死所有在途付费 AI 请求，而 `SupervisorJob` 只隔离
     * 子协程之间的失败，挡不住来自父级的取消。测试要控制生命周期应注入自己的 `TestScope`。
     */
    @Inject
    @ApplicationCoroutineScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // 在后台线程初始化离线词典
        applicationScope.launch {
            try {
                dictionaryRepository.initializeFromAssets()
            } catch (cancellation: CancellationException) {
                // initializeFromAssets 是 suspend，取消须原样上抛而不能并入下面的通用分支。
                // applicationScope 生命周期与进程一致、正常运行期不会被取消，所以这里更多是
                // 守住不变量：一旦将来换成更短的 scope 或在测试中主动取消，吞掉取消会让协程
                // 以「正常完成」收尾，并打出一条声称初始化失败的误导日志。
                throw cancellation
            } catch (e: Exception) {
                Log.e("EnglishReaderApp", "Dictionary initialization failed - offline lookup will not work", e)
                // 不阻塞 App 启动，离线查询会返回 null，自动降级到在线 API
            }
        }
    }
}
