package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 一篇文章的分块偏好与**当前已发布**的对照布局。
 *
 * 与任务表分开是刻意的：任务是一次可能失败、可取消、可删除的执行过程，而已发布布局是阅读页此刻
 * 该怎么显示译文的事实。把布局挂在任务上，删一条历史任务就会让用户正在读的对照突然退回整段模式。
 * 因此这里对 `whole_translation_tasks` **不设外键**，[appliedTaskId] 只用于溯源。
 *
 * 对 `articles` 设 CASCADE：文章没了，它的偏好与布局也没有任何意义。
 */
@Entity(
    tableName = "article_translation_state",
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ArticleTranslationStateEntity(
    @PrimaryKey
    val articleId: Long,

    /**
     * 下次新建任务使用的分块方式 token，见 `TranslationSegmentationMode.toStableToken`。
     *
     * 只影响**下一次显式启动**。改偏好绝不重排已发布的对照，也不动正在跑的任务——那会让用户点
     * 一下设置就产生一批新的付费请求。
     */
    val preferredMode: String,

    /**
     * 已发布布局的版本化 JSON 快照；从未成功发布过时为 null。
     *
     * 存的是坐标而不是正文：每块的原文区间，以及它的译文在**按原段聚合后的完整中文串**里的区间。
     * 阅读页据此把一段中文切回块，而不是按空行或中文句数去猜——块内本就可能含换行，按分隔符反切
     * 会整体错位一格。
     */
    val appliedPlan: String? = null,

    /**
     * 发布时 `ArticleEntity.content` 的指纹。
     *
     * 与正文当前指纹不符即说明正文已被编辑过，布局里的 offset 不再指向同样的字符，必须回落到整段
     * 显示而不是继续裁切。
     */
    val appliedSourceFingerprint: String? = null,

    /**
     * 发布时写入 `ArticleEntity.translation` 的最终译文指纹。
     *
     * 单独校验译文而非只看正文：译文可能被其他路径覆盖，而布局里的中文 offset 只对那一份确切的
     * 译文字符串成立。
     */
    val appliedTranslationFingerprint: String? = null,

    /** 产出当前布局的任务 ID，仅用于溯源与发布身份比较；任务被删除后仍保留。 */
    val appliedTaskId: Long? = null,

    val updatedAt: Long
)
