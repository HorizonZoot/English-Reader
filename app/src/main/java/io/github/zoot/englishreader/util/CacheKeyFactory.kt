package io.github.zoot.englishreader.util

import java.security.MessageDigest

/**
 * 缓存键生成工具
 *
 * 使用 SHA-256 算法为 AI 解释生成唯一的缓存键
 * 使用  (Unit Separator) 分隔多个输入，避免拼接冲突
 *
 * 纯 Kotlin 实现，不依赖 Android Framework，支持 JVM 单元测试
 */
object CacheKeyFactory {

    /**
     * 分隔符：ASCII 控制字符 Unit Separator (0x1F)
     * 用于分隔多个输入，避免拼接冲突
     * 例如：["AB", "C"] 和 ["A", "BC"] 生成不同的哈希
     */
    private const val SEPARATOR = ""

    /**
     * 生成缓存键
     *
     * @param inputs 一个或多个输入字符串
     * @return SHA-256 哈希值（64 位十六进制字符串）
     */
    fun generate(vararg inputs: String): String {
        // 使用分隔符连接所有输入
        val combined = inputs.joinToString(SEPARATOR)

        // 计算 SHA-256 哈希
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(combined.toByteArray(Charsets.UTF_8))

        // 转换为十六进制字符串
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
