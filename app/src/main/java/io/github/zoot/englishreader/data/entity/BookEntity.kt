package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书籍实体。
 *
 * 一本书是「多个可独立阅读的章节」的聚合，章节正文仍存在 [ArticleEntity]，
 * 由 [BookChapterEntity] 维护归属与顺序。这样做的代价是 articles 表同时承载
 * 「单篇文章」和「书籍章节」两种角色，收益是 ReadingScreen、词典、TTS、AI 解释缓存
 * 和生词全部以 articleId 为轴，无需为章节再造一条平行链路。
 */
@Entity(
    tableName = "books",
    indices = [
        Index(value = ["identifier"]),
        Index(value = ["contentFingerprint"])
    ]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 书名（来自 dc:title，缺失时回退文件名） */
    val title: String,

    /** 作者（dc:creator），缺失为 null */
    val author: String? = null,

    /** 语言（dc:language），缺失为 null */
    val language: String? = null,

    /**
     * EPUB 的 dc:identifier。
     *
     * 去重首选键。**不是**主键：合法 EPUB 允许缺失该字段，且不同来源的同一本书
     * 可能带不同 identifier，用它做主键会让去重逻辑与存储结构耦死。
     */
    val identifier: String? = null,

    /**
     * 内容指纹。
     *
     * [identifier] 缺失时的去重依据。必须与章节顺序绑定——只用「章节数 + 总字符数」
     * 会让同一批章节的不同排版顺序被误判为同一本书。
     */
    val contentFingerprint: String,

    /** 源格式：`epub2` 或 `epub3`。用字符串而非 enum 存储，避免为单个字段新增 TypeConverter。 */
    val sourceFormat: String,

    /** 实际入库的章节数（已排除空正文项），与 book_chapters 行数一致 */
    val chapterCount: Int,

    /** 全书正文总字符数，用于书架展示与预算校验回溯 */
    val totalChars: Int,

    /** 导入时间（时间戳） */
    val createdAt: Long = System.currentTimeMillis(),

    /** 最近阅读时间（时间戳），未读为 null */
    val lastReadAt: Long? = null
)

/** 书籍源格式常量。与 [BookEntity.sourceFormat] 的取值一一对应。 */
object BookSourceFormat {
    const val EPUB2 = "epub2"
    const val EPUB3 = "epub3"
}
