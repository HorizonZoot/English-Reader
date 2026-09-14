package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书籍章节关系。
 *
 * 只维护「哪本书的第几章对应哪条 article」，不存正文——正文在 [ArticleEntity]。
 *
 * **删书时不能直接依赖本表的 CASCADE 清理章节正文**：删 books 行只会级联删掉本表的
 * 关系行，articles 会变成孤儿。必须显式删除章节 article，且删之前先把
 * `vocabulary.articleId` 解绑为 NULL——vocabulary 对 articles 是 CASCADE，
 * 顺序颠倒会静默丢掉用户生词。
 */
@Entity(
    tableName = "book_chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // 同一本书内章节序号唯一：防止重复导入或事务半成品造成顺序错乱
        Index(value = ["bookId", "chapterIndex"], unique = true),
        // 一条 article 只能属于一本书的一个章节位置
        Index(value = ["articleId"], unique = true),
        Index(value = ["bookId"])
    ]
)
data class BookChapterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 所属书籍 */
    val bookId: Long,

    /** 章节正文所在的 article */
    val articleId: Long,

    /**
     * 章节序号，0-based，按 reading order。
     *
     * 空正文项被过滤后**重新连续编号**，故它不等于原始 spine 下标。
     * 保持连续是为了让「上一章/下一章」不必处理空洞。
     */
    val chapterIndex: Int,

    /** 规范化后的 OCF 路径，不含 fragment。用于回溯与调试，不参与阅读渲染。 */
    val sourceHref: String,

    /** NAV/NCX 里的目录标题；目录未覆盖该项时为 null（此时章节标题已在 article.title 里回退推导） */
    val navigationTitle: String? = null
)
