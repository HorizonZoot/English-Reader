package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 离线词典条目
 *
 * 内置基础词典（约 7000 词，见 [DictionaryRepository] 的 `DICT_VERSION` 注释），提供中英双语释义
 * 数据来源：ECDICT (https://github.com/skywind3000/ECDICT)
 */
@Entity(tableName = "dictionary")
data class DictionaryEntry(
    /** 单词（小写） */
    @PrimaryKey
    val word: String,

    /** 音标（IPA 格式，可选） */
    val phonetic: String?,

    /** 中文释义（多个释义用"；"分隔） */
    val chinese: String,

    /** 英文定义（可选，简短说明） */
    val english: String?
)
