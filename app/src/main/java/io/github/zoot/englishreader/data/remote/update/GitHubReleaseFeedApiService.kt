package io.github.zoot.englishreader.data.remote.update

import io.github.zoot.englishreader.data.update.UpdateReleasePolicy
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Streaming

interface GitHubReleaseFeedApiService {
    @Streaming
    @Headers("Accept: application/atom+xml")
    @GET(UpdateReleasePolicy.FEED_PATH)
    suspend fun getReleaseFeed(): ResponseBody
}
