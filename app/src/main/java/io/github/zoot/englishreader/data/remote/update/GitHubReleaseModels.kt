package io.github.zoot.englishreader.data.remote.update

import com.squareup.moshi.Json

/**
 * GitHub Release 的最小投影。
 *
 * **只声明本功能真正需要的四个字段。** Moshi 默认忽略未知字段，所以 assets、author、
 * draft、prerelease、published_at 等一概不建模——把整个 payload 映射出来只会制造大量
 * 无人消费的字段，而每一个都是将来某人以为「有人在用」的负担。
 *
 * [name] 与 [body] 都可空：GitHub 对这两个字段都可能返回 `null`（未填标题的 release、
 * 没有正文的 release）。[tagName] 与 [htmlUrl] 在 API 契约里必然存在。
 */
data class GitHubRelease(
    /** 远端版本号，形如 `v0.1-beta`。交给 `AppVersion` 解析。 */
    @Json(name = "tag_name") val tagName: String,
    /** Release 标题。 */
    @Json(name = "name") val name: String?,
    /** 更新概要（Markdown）。弹窗的核心内容。 */
    @Json(name = "body") val body: String?,
    /** Release 页面地址。「立即更新」打开它——这不是 APK 下载链接。 */
    @Json(name = "html_url") val htmlUrl: String
)
