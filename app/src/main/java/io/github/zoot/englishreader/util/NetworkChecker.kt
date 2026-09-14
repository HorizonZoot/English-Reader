package io.github.zoot.englishreader.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络连通性检测。
 *
 * 抽象成接口是为了让 [io.github.zoot.englishreader.viewmodel.ReadingViewModel] 的单元测试
 * 可注入 mock（真机 ConnectivityManager 无法在 JVM 测试中构造）。
 */
interface NetworkChecker {
    /** 当前是否有可用（已验证可达外网）的网络连接。 */
    fun isOnline(): Boolean
}

/**
 * 基于 [ConnectivityManager] 的实现。
 *
 * 用 NET_CAPABILITY_VALIDATED 判断"连上且确实能访问外网"，而非仅 NET_CAPABILITY_INTERNET
 * （后者在连了 WiFi 但无实际外网、门户认证未通过时仍为 true，会误判为在线）。
 * 需 ACCESS_NETWORK_STATE 权限（已在 AndroidManifest 声明）。
 */
@Singleton
class AndroidNetworkChecker @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkChecker {

    override fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
