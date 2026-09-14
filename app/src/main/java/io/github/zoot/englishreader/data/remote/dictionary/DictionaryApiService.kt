package io.github.zoot.englishreader.data.remote.dictionary

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Free Dictionary API 服务接口
 *
 * Base URL: https://api.dictionaryapi.dev/api/v2/entries/en/
 * 文档: https://dictionaryapi.dev/
 */
interface DictionaryApiService {

    /**
     * 查询单词
     *
     * @param word 要查询的单词
     * @return 单词的详细信息（音标、词性、释义等）
     */
    @GET("{word}")
    suspend fun getWordDefinition(@Path("word") word: String): List<DictionaryResponse>
}
