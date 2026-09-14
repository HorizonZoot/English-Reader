package io.github.zoot.englishreader.data.dictionary

import android.content.Context
import io.github.zoot.englishreader.data.entity.DictionaryEntry

/** 启动初始化与移除扩展词库共用同一份内置数据和版本。读取失败交给调用边界处理。 */
internal object BuiltInDictionary {
    const val VERSION = 2

    fun read(context: Context): List<DictionaryEntry> =
        context.assets.open("dict_base.tsv").bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 3) null else DictionaryEntry(
                    word = parts[0].trim().lowercase(),
                    phonetic = parts[1].trim().takeIf { it.isNotBlank() },
                    chinese = parts[2].trim(),
                    english = parts.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() }
                )
            }.toList()
        }
}
