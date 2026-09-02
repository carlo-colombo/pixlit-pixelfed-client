package ovh.litapp.pixlit.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface BlueskyApi {
    @GET("xrpc/app.bsky.actor.getProfile")
    suspend fun getProfile(@Query("actor") actor: String): Response<BlueskyProfile>

    @GET("xrpc/app.bsky.feed.getPosts")
    suspend fun getPosts(@Query("uris") uri: String): Response<BlueskyPosts>
}

data class BlueskyProfile(@SerializedName("pinnedPost") val pinnedPost: PinnedPost? = null)
data class PinnedPost(val uri: String? = null)
data class BlueskyPosts(val posts: List<BlueskyPost> = emptyList())
data class BlueskyPost(
    val record: BlueskyRecord? = null,
    val embed: BlueskyEmbed? = null
)
data class BlueskyRecord(val text: String? = null, val embed: BlueskyEmbed? = null)
data class BlueskyEmbed(val images: List<BlueskyImage>? = null)
data class BlueskyImage(val alt: String? = null)
