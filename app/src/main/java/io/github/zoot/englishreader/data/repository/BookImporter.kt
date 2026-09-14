package io.github.zoot.englishreader.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.zoot.englishreader.data.importer.EpubBookParser
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportBudgetValidator
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.importer.ImportedBook
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 整本书导入器：SAF URI → 有界临时文件 → [EpubBookParser]。
 *
 * 与 [ArticleImporter] 并列，是整本书路径唯一的 IO façade。为什么必须落临时文件：
 * Readium 的 `AssetRetriever` 需要可随机访问的 `File`，而 SAF 只给单向 stream。
 * 这与现有 [io.github.zoot.englishreader.data.importer.EpubTextExtractor] 的
 * ZipFile 路径是同一个理由。
 *
 * 临时文件在 finally 中删除，包含取消路径——付费/长耗时操作被取消时，
 * 不能把用户书籍的明文副本留在 cacheDir。
 */
@Singleton
class BookImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: EpubBookParser
) {

    /**
     * 从 SAF URI 导入整本书。
     *
     * @throws ImportException 所有失败都带 [ImportFailure]
     * @throws CancellationException 原样上抛，绝不吞掉
     */
    suspend fun importFromUri(uri: Uri): ImportedBook = withContext(Dispatchers.IO) {
        val temp = copyToTempFile(uri)
        try {
            val book = parser.parse(temp)
            // 标题归一化与单篇导入共用同一入口，避免两条路径对长度/空白处理不一致。
            book.copy(
                metadata = book.metadata.copy(
                    title = ImportBudgetValidator.normalizeTitle(
                        book.metadata.title.takeIf { it.isNotBlank() }
                            ?: queryDisplayName(uri)?.removeSuffix(".epub"),
                        ArticleImporter.DEFAULT_TITLE
                    )
                )
            )
        } finally {
            deleteTemp(temp)
        }
    }

    /**
     * 有界复制到 cacheDir。
     *
     * 逐块检查取消：整本书可以有上百 MB，没有检查点时用户返回后复制仍在后台跑完。
     */
    private suspend fun copyToTempFile(uri: Uri): File {
        val temp = File.createTempFile("book-import-", ".epub", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ImportException(ImportFailure.SourceUnreadable)
            input.use { source ->
                temp.outputStream().use { sink ->
                    val limit = ImportBudget.MAX_EPUB_ARCHIVE_BYTES
                    val buf = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        // 逐块检查点：整本书可达数十 MB，缺少检查点时用户返回后
                        // 复制仍会在后台跑完。
                        coroutineContext.ensureActive()
                        val read = source.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > limit) {
                            throw ImportException(ImportFailure.SourceTooLarge(limit))
                        }
                        sink.write(buf, 0, read)
                    }
                }
            }
        } catch (e: CancellationException) {
            deleteTemp(temp)
            throw e
        } catch (e: ImportException) {
            deleteTemp(temp)
            throw e
        } catch (e: Exception) {
            deleteTemp(temp)
            throw ImportException(ImportFailure.SourceUnreadable)
        }
        return temp
    }

    /** 删除失败不抛异常，但不静默——留下的临时文件含用户书籍内容。 */
    private fun deleteTemp(file: File) {
        if (file.exists() && !file.delete()) {
            android.util.Log.w(TAG, "Failed to delete temp book archive")
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
        private const val TAG = "BookImporter"
        private const val COPY_BUFFER_BYTES = 8192
    }
}
