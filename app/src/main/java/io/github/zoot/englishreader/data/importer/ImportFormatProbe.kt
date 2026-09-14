package io.github.zoot.englishreader.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 判定一个 SAF URI 的导入格式，供上层决定走「单篇文章」还是「整本书」。
 *
 * ## 为什么单独成类
 *
 * 这是一个**路由决策**，既不属于 [io.github.zoot.englishreader.data.repository.ArticleImporter]
 * 也不属于 [io.github.zoot.englishreader.data.repository.BookImporter]——两者都是决策的结果，
 * 不是决策本身。若把它塞进任一方，ViewModel 就得先问 A「这个文件该不该给 B」，
 * 依赖方向会绕。
 *
 * ViewModel 不直接读 ContentResolver（那是 IO façade 的职责），所以探测必须有一个
 * 可注入的落点。
 *
 * ## 只读头部
 *
 * 判格式只需前若干字节的签名 + 文件名 + MIME，不需要整个文件。EPUB 走
 * `ZipFile` 随机访问（需要临时文件），文本类走一次性有界读取，两条路径读流的方式不同，
 * 因此必须先判再读——这也是 [io.github.zoot.englishreader.data.repository.ArticleImporter]
 * 内部同样先探测的原因。
 */
@Singleton
class ImportFormatProbe @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 探测格式。
     *
     * @throws ImportException [ImportFailure.SourceUnreadable] 当流打不开时。
     *   不在这里兜底成某个默认格式：拿不到内容就无法判断，猜错会让后续解析报出
     *   与真实原因无关的错误。
     * @throws kotlinx.coroutines.CancellationException 原样上抛
     */
    suspend fun detect(uri: Uri): ImportFormat = withContext(Dispatchers.IO) {
        val head = readHead(uri)
        val displayName = queryDisplayName(uri)
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        ImportFormatDetector.detect(head, displayName, mimeType)
    }

    private fun readHead(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException(ImportFailure.SourceUnreadable)
        return input.use {
            val buf = ByteArray(HEAD_PROBE_BYTES)
            var filled = 0
            while (filled < buf.size) {
                val read = it.read(buf, filled, buf.size - filled)
                if (read < 0) break
                filled += read
            }
            buf.copyOf(filled)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.getOrNull()

    private companion object {
        /** 判 ZIP 签名只需 4 字节，取 64 留余量。与 ArticleImporter 的探测长度一致。 */
        const val HEAD_PROBE_BYTES = 64
    }
}
