package io.github.zoot.englishreader.model

import java.text.Normalizer

/** 阅读快照与 AI 请求准备共用的规范化契约。 */
object AiExplanationTextNormalizer {
    /** 做 NFC 规范化、换行符规范化和首尾裁剪，但不合并内部空白。 */
    fun normalize(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
}
