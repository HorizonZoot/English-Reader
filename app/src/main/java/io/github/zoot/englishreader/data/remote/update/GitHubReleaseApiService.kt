package io.github.zoot.englishreader.data.remote.update

import retrofit2.http.GET
import retrofit2.http.Headers

/**
 * GitHub Releases API。
 *
 * Base URL: `https://api.github.com/`
 * 文档: https://docs.github.com/en/rest/releases/releases#list-releases
 *
 * 仓库路径写死在下面的注解里，**只此一处**。本应用只查自己的 release，没有「切换仓库」这种
 * 需求，把 owner/repo 做成 `@Path` 参数只会让调用方每次重复传同样的字面量，并给未来留下
 * 一个没人消费的配置点——`DictionaryModule` 的注释记着本项目吃过那种亏。
 *
 * 匿名调用：这是公开仓库，不需要认证。不要引入 token。
 */
interface GitHubReleaseApiService {

    /**
     * 取已发布 release 列表的第一项，包含 prerelease；匿名请求不会返回 draft。
     *
     * `/latest` 排除 prerelease，不能用于本应用的 beta 发布。
     * `Accept` 头是 GitHub 官方推荐的版本协商方式，省略时行为取决于服务端默认值。
     */
    @Headers("Accept: application/vnd.github+json")
    @GET("repos/HorizonZoot/English-Reader/releases?per_page=1")
    suspend fun getPublishedReleases(): List<GitHubRelease>
}
