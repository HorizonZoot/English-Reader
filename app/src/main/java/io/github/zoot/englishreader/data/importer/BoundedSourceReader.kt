package io.github.zoot.englishreader.data.importer

import java.io.InputStream

/**
 * 有界字节读取器。
 *
 * 改造前 reader.readText() 一次性把整个文件读进内存，选一个 50MB 的 txt 直接 OOM。
 *
 * 这里刻意采用「一次开流 → 最多读 limit + 1 字节 → 在这段有界数组上做后续全部判定」的
 * 管线，而不是「前若干 KB 采样判编码」。反例：前 8KB 全是 ASCII、第 50000 字节才出现
 * GBK 中文，采样会判成 UTF-8，随后严格解码在流中途失败，而 InputStream 已被消费掉了。
 *
 * ⚠️ 调用方 ArticleImporter 为了先判格式，实际会开流两次（先读头部若干字节判 ZIP 签名，
 * 再重开读全文）。标准 SAF provider 支持重复打开，但云盘/第三方 DocumentsProvider 可能
 * 二次打开失败或触发重新下载——这是已知取舍：格式判定必须先于读取方式的选择，
 * 因为 EPUB 走 ZipFile 随机访问、文本走一次性有界读取，两条路径读流的方式不同。
 *
 * 多读的那 1 个字节是「是否超限」的判据：读满 limit + 1 说明源文件至少 limit + 1 字节，
 * 必然超限；此时立即停止，不继续扫完剩余内容——为了显示精确字数去读完 50MB 不可接受。
 */
object BoundedSourceReader {

    /**
     * 从 [input] 最多读取 [limitBytes] 字节。
     *
     * @return 读到的全部字节（长度 <= limitBytes）
     * @throws ImportException [ImportFailure.SourceTooLarge] 源长度超过 limitBytes
     */
    fun readAtMost(input: InputStream, limitBytes: Int): ByteArray {
        require(limitBytes > 0) { "limitBytes must be positive: $limitBytes" }

        // 读 limit + 1 字节的空间：多出来的那一字节一旦被填满即证明超限。
        val buffer = ByteArray(limitBytes + 1)
        var filled = 0

        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }

        if (filled > limitBytes) {
            throw ImportException(ImportFailure.SourceTooLarge(limitBytes))
        }

        return buffer.copyOf(filled)
    }
}
