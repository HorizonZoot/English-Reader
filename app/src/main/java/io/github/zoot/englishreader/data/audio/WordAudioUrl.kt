package io.github.zoot.englishreader.data.audio

import java.net.URLEncoder

/**
 * 单词发音的音频地址。
 *
 * 有道 `dictvoice` 对任意单词都能合成，**不依赖词典是否收录**——所以生词本里那些
 * 离线词库查不到释义的词（7,005 条之外）照样有读音。
 *
 * 与 [PronunciationAudioCache] 同包：缓存的就是这个地址的内容，两者是同一件事的两端。
 * 单独抽出来是因为阅读页和生词本都要发音，而 URL 格式只能有一处定义。
 */
object WordAudioUrl {

    /**
     * `type=2` 是美式发音，`type=1` 是英式。阅读页原本就用 2，保持不变。
     */
    fun forWord(word: String): String {
        val encoded = URLEncoder.encode(word, "UTF-8")
        return "https://dict.youdao.com/dictvoice?audio=$encoded&type=2"
    }
}
