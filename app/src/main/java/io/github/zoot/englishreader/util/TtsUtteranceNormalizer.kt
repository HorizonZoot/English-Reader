package io.github.zoot.englishreader.util

/**
 * 送进 TTS 引擎前的文本归一化。**只改引擎收到的字符串**，显示文本、`SentenceRange`
 * 偏移和高亮一概不碰——它们由调用方持有原文，这里的返回值不会流回 UI。
 *
 * 阅读页逐句提交 utterance，所以每一句的第一个词对引擎来说都没有上文。系统引擎在这个
 * 位置会把首字母大写的短词 `It` 猜成缩写、逐字母拼读；真机上小写 `it` 读音正确
 * （单词发音路径本就 `lowercase()`，读对了）。
 *
 * 只处理白名单而不是整句小写：`Reading`（英国地名读 /ˈredɪŋ/）、`Polish`/`polish`、
 * `Nice`/`nice` 小写后读音会变，`NASA` 这类全大写缩写也不该动。白名单只收「首字母大写、
 * 其余小写」的形态，真正的缩写 `IT` 不会命中。
 */
object TtsUtteranceNormalizer {

    /** 跳过起始引号/括号（小说对话常以 `“It…` 开头）；允许 `It's` / `It’s`（电子书常用弯引号）。 */
    private val leadingWord = Regex("^[\\s\"'“‘(\\[]*([A-Za-z]+(?:['’][A-Za-z]+)?)")

    private val ambiguousLeadingWords = setOf(
        "It", "Its",
        "It's", "It’s",
        "It'll", "It’ll",
        "It'd", "It’d"
    )

    fun normalize(text: String): String {
        val word = leadingWord.find(text)?.groups?.get(1) ?: return text
        if (word.value !in ambiguousLeadingWords) return text
        return text.replaceRange(word.range, word.value.lowercase())
    }
}
