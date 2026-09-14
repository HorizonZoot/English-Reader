package io.github.zoot.englishreader.data.dictionary

/**
 * 扩展词库的安装状态。
 *
 * 内置的 assets 词库（约 7,000 词）永远可用，是不需要下载的基线；本状态描述的是**可选的**
 * ECDICT 扩展词库（约 76 万词）。因此 [NotInstalled] 不是错误状态，只是「用户还没选择下载」。
 *
 * 下载必须由用户显式触发：这是 60 MB 级别的流量，不能在用户没同意的情况下发起。
 */
sealed interface DictionaryPackState {

    /** 未安装扩展词库，查词走内置的 7,000 词基线。 */
    data object NotInstalled : DictionaryPackState

    /**
     * 正在下载。
     *
     * @param downloadedBytes 已接收字节
     * @param totalBytes 服务器声明的总字节；HTTP 未给 Content-Length 时为 null，
     *   此时界面只能显示已下载量而无法显示百分比。
     */
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long?
    ) : DictionaryPackState {
        /** 0f..1f，总量未知时为 null。 */
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let {
                (downloadedBytes.toFloat() / it).coerceIn(0f, 1f)
            }
    }

    /**
     * 下载完成，正在解析并写入数据库。
     *
     * 与 [Downloading] 分开是因为这一步对用户的感受不同：网络已经断开也能继续，
     * 而且它会持续数十秒（76 万行 CSV 解析 + Room 批量写入），若沿用「下载中」文案
     * 用户会以为卡住了。
     */
    data class Installing(val processedEntries: Int) : DictionaryPackState

    /** 已安装。[entryCount] 是数据库里的实际条数，不是声明值。 */
    data class Installed(val entryCount: Int) : DictionaryPackState

    /** 正在以事务恢复内置词库；提交期间禁止再次安装或移除。 */
    data object Removing : DictionaryPackState

    /**
     * 失败。
     *
     * 失败后**内置词库仍然可用** —— 安装走 `replaceAll` 事务，回滚后版本号未落盘，
     * 下次启动会重新从 assets 初始化。所以这个状态对用户的含义是「扩展词库没装上」，
     * 不是「查词坏了」。
     */
    data class Failed(val reason: DictionaryPackFailure) : DictionaryPackState
}

enum class DictionaryPackRemovalResult { REMOVED, FAILED, BUSY }

/**
 * 扩展词库安装失败的原因。
 *
 * 分类的目的是给出**用户能采取的下一步**，而不是罗列技术细节：网络问题让用户重试，
 * 存储不足让用户清空间，数据损坏让用户重试（可能是传输中断）。
 */
enum class DictionaryPackFailure {
    /** 无网络、连接超时、传输中断。 */
    NETWORK,

    /** 磁盘空间不足。60 MB 下载 + 解析期间的数据库增长都可能触发。 */
    STORAGE,

    /** 下载完成但内容不是预期的 CSV（比如拿到一个错误页面）。 */
    CORRUPT,

    /** 用户主动取消。不是错误，但状态机需要一个可区分的落点。 */
    CANCELLED,

    /** 其余未归类。 */
    UNKNOWN
}
