package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.remote.ai.AiChatMessage

/**
 * 解释请求的纯上下文与 prompt 策略。
 *
 * 准备阶段发生在 profile 解析、凭据访问、缓存和操作归属之前。返回的 messages 与元数据是
 * 一份不可变来源，同时驱动对外请求和缓存身份。
 */
internal object AiPromptPolicy {

    fun prepare(input: AiExplanationInput): AiPromptPreparationResult {
        val normalizedInput = AiTextNormalizer.normalizeInput(input.text)
        if (normalizedInput.isBlank()) {
            return AiPromptPreparationResult.Rejected(AiError.NoContent)
        }

        // 长度闸门对**所有**输入类型生效，不只文章。
        //
        // 原先只挡文章，依据是「句子长度天然受 UI 选择行为约束」。该前提不成立：
        // SentenceSplitter 对没有句末标点的段落会把整段作为单个 SentenceRange 返回
        // （见 docs 审查记录第五节第 6 项），而 MAX_IMPORT_CHARS 是 40000。于是用户点击
        // 那个「一句」就能把最多 40000 字符发往远端翻译——付费、且远端只会回 413/400，
        // 界面显示成通用网络错误，用户无法自行修正。
        val maxChars = ImportBudget.MAX_FULL_EXPLANATION_CHARS
        if (normalizedInput.length > maxChars) {
            return AiPromptPreparationResult.Rejected(
                AiError.InputTooLong(
                    actualChars = normalizedInput.length,
                    maxChars = maxChars
                )
            )
        }

        val spec = when (input) {
            is AiExplanationInput.Sentence -> SENTENCE_SPEC
            is AiExplanationInput.SentenceTranslation -> TRANSLATION_SPEC
            is AiExplanationInput.ParagraphTranslation -> PARAGRAPH_TRANSLATION_SPEC
            is AiExplanationInput.Article -> ARTICLE_SPEC
        }

        return AiPromptPreparationResult.Ready(
            PreparedAiPrompt(
                normalizedInput = normalizedInput,
                explanationType = spec.explanationType,
                promptVersion = spec.promptVersion,
                messages = listOf(
                    AiChatMessage(role = "system", content = spec.systemInstruction),
                    AiChatMessage(role = "user", content = normalizedInput)
                )
            )
        )
    }

    private class PromptSpec(
        val explanationType: ExplanationType,
        val promptVersion: String,
        val systemInstruction: String
    ) {
        override fun toString(): String =
            "PromptSpec(" +
                "explanationType=${explanationType.toStableToken()}, " +
                "promptVersion=$promptVersion, " +
                "systemInstruction=[REDACTED])"
    }

    private val SENTENCE_SPEC = PromptSpec(
        explanationType = ExplanationType.SENTENCE_EXPLANATION,
        promptVersion = "sentence-context-v1",
        systemInstruction =
            "Explain only the provided English sentence in concise Simplified Chinese. " +
                "Do not assume surrounding context."
    )

    private val ARTICLE_SPEC = PromptSpec(
        explanationType = ExplanationType.ARTICLE_EXPLANATION,
        promptVersion = "article-context-v1",
        systemInstruction =
            "Explain only the provided English article in concise Simplified Chinese. " +
                "Use no context outside the article."
    )

    /**
     * 段落翻译。
     *
     * 明确要求「单个段落、不要空行」不是排版洁癖：成功的段落译文最终会用空行连接成
     * `ArticleEntity.translation`，而 `ParagraphAligner` 按空行反向切分并**按下标**与英文段落
     * 配对。任何一段译文内部含空行，都会让它之后的所有段落整体错位一格。代码侧仍会做
     * 规范化兜底，prompt 只是第一道防线。
     */
    private val PARAGRAPH_TRANSLATION_SPEC = PromptSpec(
        explanationType = ExplanationType.PARAGRAPH_TRANSLATION,
        promptVersion = "paragraph-translation-v1",
        systemInstruction =
            "Translate the supplied English paragraph into natural Simplified Chinese. " +
                "Treat it as one self-contained paragraph and do not assume surrounding context. " +
                "Return only the translation as a single paragraph, with no blank lines, labels, " +
                "Markdown, commentary, or quotation marks."
    )

    private val TRANSLATION_SPEC = PromptSpec(
        explanationType = ExplanationType.SENTENCE_TRANSLATION,
        promptVersion = "sentence-translation-v1",
        systemInstruction =
            "Translate only the supplied English sentence into natural Simplified Chinese. " +
                "Return only the translation without labels, Markdown, commentary, or quotation marks."
    )
}

internal sealed interface AiPromptPreparationResult {
    data class Ready(val prompt: PreparedAiPrompt) : AiPromptPreparationResult

    data class Rejected(val error: AiError) : AiPromptPreparationResult
}

internal data class PreparedAiPrompt(
    val normalizedInput: String,
    val explanationType: ExplanationType,
    val promptVersion: String,
    val messages: List<AiChatMessage>
) {
    /** prompt 内容源自用户，绝不能出现在诊断信息里。 */
    override fun toString(): String =
        "PreparedAiPrompt(" +
            "normalizedInput=[REDACTED], " +
            "explanationType=${explanationType.toStableToken()}, " +
            "promptVersion=$promptVersion, " +
            "messageCount=${messages.size})"
}
