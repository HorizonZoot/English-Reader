package io.github.zoot.englishreader.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit Rule：将 Dispatchers.Main 替换为可控的 TestDispatcher。
 *
 * ViewModel 的 viewModelScope 默认跑在 Dispatchers.Main，单元测试环境没有
 * Android 主线程 Looper，直接跑会抛异常。此 Rule 在每个测试前 setMain、
 * 测试后 resetMain。
 *
 * 默认使用 UnconfinedTestDispatcher：viewModelScope.launch 会立即 eager 执行，
 * 适配"触发操作后立刻断言"的测试写法，无需手动 advanceUntilIdle()。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: kotlinx.coroutines.test.TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
