package io.github.zoot.englishreader.data.remote.ai

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
class AiModelListResponse(val data: List<AiModelDto>? = null) {
    override fun toString(): String = "AiModelListResponse(count=${data?.size ?: 0})"
}

@JsonClass(generateAdapter = true)
class AiModelDto(val id: String? = null) {
    override fun toString(): String = "AiModelDto(id=[REDACTED])"
}
