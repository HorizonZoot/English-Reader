package io.github.zoot.englishreader.ui.screen.vocabulary

/**
 * 生词卡片上除单词本身之外的补充信息。
 *
 * 释义来自内置离线词典（[io.github.zoot.englishreader.data.repository.DictionaryRepository.lookupOffline]），
 * 在生词本打开时按需解析并缓存。词库（7,005 条 COCA 高频词）未收录的生词
 * [chinese] 为 null，卡片只显示单词本身，不显示占位文案——空白比「未找到释义」更安静。
 *
 * @param phonetic 音标，来自词典条目
 * @param chinese 中文释义，原始形态（多个义项以「；」分隔），由界面截断显示
 * @param headword 词形还原命中的原形。生词存的是变形词（lives）而词典收回原形（live）时非空，
 *   界面据此标注；直接命中时为 null
 * @param sourceTitle 来源文章标题；生词未关联文章，或文章已不存在时为 null
 */
data class VocabularyWordDetail(
    val phonetic: String? = null,
    val chinese: String? = null,
    val headword: String? = null,
    val sourceTitle: String? = null
) {
    /** 该生词是否有可展示的释义。 */
    val hasGloss: Boolean get() = !chinese.isNullOrBlank()
}
