package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.util.ParagraphAligner

/**
 * `WholeTranslationDao.materialize` 需要的全部纯计算。
 *
 * 收成一个接口而不是继续往事务方法上加函数参数：这个事务已经要做「按目标版本分派 → 校验 →
 * 聚合 → 编码 → 写入」五件事，六七个 lambda 参数在调用处会退化成一串无名的括号，谁对应谁全靠
 * 位置。接口同时让「哪些计算是纯的、可以安全地在事务里跑」成为一份明确清单。
 *
 * 实现必须是纯函数：它们在 Room 事务内被调用，做 IO 或抛异常都会连带回滚整个发布。
 */
interface TranslationMaterializationPolicy {

    /** 必须与 `ParagraphAligner.splitParagraphs` 同源，否则块坐标与渲染分段会分叉。 */
    fun splitParagraphs(content: String): List<String>

    /** legacy 路径：把逐段译文拼成文章译文。 */
    fun joinParagraphs(translations: List<String>): String

    /** 正文指纹，用于确认任务运行期间正文未被编辑。 */
    fun articleFingerprint(content: String): String

    /** 最终译文指纹，随布局一起存，供阅读层确认布局仍对应这一份译文。 */
    fun translationFingerprint(translation: String): String

    /** block 路径：按原段聚合块译文，并同时产出中文坐标。 */
    fun aggregate(
        paragraphs: List<String>,
        blocks: List<TranslatedBlock>
    ): TranslationBlockAggregator.Result

    /** 把布局编码成可持久化的结构化文本。 */
    fun encodeLayout(layout: AppliedTranslationLayout): String

    /**
     * 解码**发布前**已有的布局，用于换算旧的中文阅读锚点。
     *
     * 解不出来（从未发布过、版本未知、与当前正文不符）时返回 null，转换据此把中文锚点退回原段起点。
     */
    fun decodeLayout(json: String?, paragraphs: List<String>): AppliedTranslationLayout?

    /**
     * 按旧布局把一个阅读锚点换算到新译文发布后仍然有效的位置。
     *
     * 必须在发布事务内做：译文与布局一改，旧的中文 offset 就指向另一份字符串了。分成两步会留下一个
     * 窗口，期间恢复阅读位置会落在无关的字符上。
     */
    fun convertAnchor(anchor: ReadingAnchor, previousLayout: AppliedTranslationLayout?): ReadingAnchor
}

/**
 * 生产实现：全部委托给各自唯一的实现，不在这里重写任何规则。
 *
 * [encodeLayout] 需要 Moshi，所以由调用方注入 codec，而不是在这里自己 new 一个 Moshi——
 * 那会让编码行为与 DI 里配置的那份适配器悄悄分叉。
 */
class DefaultTranslationMaterializationPolicy(
    private val layoutCodec: AppliedTranslationLayoutCodec
) : TranslationMaterializationPolicy {

    override fun splitParagraphs(content: String): List<String> =
        ParagraphAligner.splitParagraphs(content)

    override fun joinParagraphs(translations: List<String>): String =
        TranslationOutputAssembler.join(translations)

    override fun articleFingerprint(content: String): String =
        TranslationFingerprint.forArticle(content)

    override fun translationFingerprint(translation: String): String =
        TranslationFingerprint.forTranslation(translation)

    override fun aggregate(
        paragraphs: List<String>,
        blocks: List<TranslatedBlock>
    ): TranslationBlockAggregator.Result = TranslationBlockAggregator.aggregate(paragraphs, blocks)

    override fun encodeLayout(layout: AppliedTranslationLayout): String =
        layoutCodec.encode(layout)

    override fun decodeLayout(json: String?, paragraphs: List<String>): AppliedTranslationLayout? =
        layoutCodec.decode(json, paragraphs)

    override fun convertAnchor(
        anchor: ReadingAnchor,
        previousLayout: AppliedTranslationLayout?
    ): ReadingAnchor =
        ReadingTranslationProjection.convertAnchorForNewPublication(anchor, previousLayout)
}
