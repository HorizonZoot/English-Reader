package io.github.zoot.englishreader.data.remote.ai

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 把 profile 的 base URL 解析成最终的 chat completions 端点。
 *
 * transport 与缓存身份**必须**共用本对象：若各自实现一份归一化，两者会随时间漂移，
 * 缓存键描述的端点就不再是真实请求的端点——那正是 6.4 要消除的一类串味。
 *
 * 归一化交给 OkHttp `HttpUrl`（小写 scheme/host、IDN 转 Punycode、省略默认端口、
 * 按 RFC 3986 处理路径），本对象只负责**拒绝**不该出现的部分并追加固定路径段。
 */
internal object AiEndpointResolver {

    /**
     * @return 规范化后的绝对 URL，形如 `https://api.deepseek.com/v1/chat/completions`
     *
     * 路径前缀**保留且区分大小写**：`/v1` 与根路径是不同服务，`/API` 与 `/api` 也是——
     * 代理常按路径分流，合并它们会让请求落到另一个后端。
     *
     * 拒绝 user-info / query / fragment，而非静默丢弃：这三者出现在 base URL 里通常意味着
     * 用户粘错了整条请求 URL 或把令牌写进了查询串。静默接受会让令牌进入缓存身份，
     * 也会让 endpoint 归一化结果与真实请求不一致。
     */
    fun chatCompletionsUrl(baseUrl: String): String {
        val parsed = baseUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid AI base URL")

        require(parsed.scheme == "https") {
            "AI base URL must use HTTPS"
        }

        require(parsed.encodedUsername.isEmpty() && parsed.encodedPassword.isEmpty()) {
            "AI base URL must not contain user-info"
        }
        require(parsed.query == null) { "AI base URL must not contain a query string" }
        require(parsed.fragment == null) { "AI base URL must not contain a fragment" }

        // addPathSegment 会把 base path 的尾斜杠视为空段并复用它，故三种尾部形态
        // （无斜杠 / 有斜杠 / 带端口）都收敛到同一结果。
        return parsed.newBuilder()
            .addPathSegment("chat")
            .addPathSegment("completions")
            .build()
            .toString()
    }
}
