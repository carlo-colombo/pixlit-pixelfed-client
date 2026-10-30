package ovh.litapp.pixlit.data.api

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The status creator validates `collection_ids` as `array|max:3`; a fourth id would 422 the whole
 * post, so the picker and the uploader both stop there.
 */
const val MAX_COLLECTIONS_PER_POST = 3

interface PixelfedApi {

    @GET("api/v1/accounts/verify_credentials")
    suspend fun verifyCredentials(
        @Header("Authorization") authHeader: String
    ): Response<AccountResponse>

    @GET("api/v1/accounts/{id}/statuses")
    suspend fun getUserStatuses(
        @Header("Authorization") authHeader: String,
        @Path("id") accountId: String,
        @Query("limit") limit: Int = 20,
        @Query("max_id") maxId: String? = null
    ): Response<List<StatusItem>>

    @FormUrlEncoded
    @POST("api/v1/apps")
    suspend fun registerApp(
        @Field("client_name") clientName: String,
        @Field("redirect_uris") redirectUris: String,
        @Field("scopes") scopes: String,
        @Field("website") website: String
    ): Response<okhttp3.ResponseBody>

    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun fetchAccessToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("scope") scope: String,
        // PKCE (SEC-03). Retrofit omits a null field, so a server that answered the authorize
        // request without a challenge still exchanges a code (the pre-PKCE flow).
        @Field("code_verifier") codeVerifier: String? = null
    ): Response<okhttp3.ResponseBody>

    @Multipart
    @POST("api/v1/media")
    suspend fun uploadMedia(
        @Header("Authorization") authHeader: String,
        @Part file: MultipartBody.Part,
        @Part("description") description: RequestBody? = null
    ): Response<MediaResponse>

    @FormUrlEncoded
    @POST("api/v1/statuses")
    suspend fun createStatus(
        @Header("Authorization") authHeader: String,
        // Carries `Idempotency-Key` (UP-05): a retried post must not create a duplicate status.
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @Field("status") status: String,
        @Field("media_ids[]") mediaIds: List<String>,
        // The status is attached to these collections in the same request (see the Pixelfed
        // `statusCreate` handle: `collection_ids` is validated as an array, max 3). This is the
        // supported path; there is no separate "add to collection" call in the app any more.
        @Field("collection_ids[]") collectionIds: List<String> = emptyList(),
        @Field("place_id") placeId: String? = null
    ): Response<StatusResponse>

    @GET("api/v1/places/search")
    suspend fun searchPlaces(
        @Header("Authorization") authHeader: String? = null,
        @Query("q") query: String? = null,
        @Query("lat") lat: Double? = null,
        @Query("lng") lng: Double? = null
    ): Response<List<PlaceItem>>

    @GET("api/v2/search")
    suspend fun searchHashtags(
        @Header("Authorization") authHeader: String? = null,
        @Query("q") query: String,
        @Query("type") type: String = "hashtags",
        @Query("limit") limit: Int = 20
    ): Response<HashtagSearchResponse>

    /**
     * `GET /api/pixelfed/v1/collections/accounts/{id}` (also exposed under `/api/v1.1/...`).
     *
     * The path is `collections/accounts/{id}`, not `accounts/{id}/collections`: the latter was a
     * 404 on every instance. The controller paginates at 9 and then maps the paginator's underlying
     * collection, so the payload is a bare array with no page envelope; `page` is 1-based.
     */
    @GET("api/pixelfed/v1/collections/accounts/{id}")
    suspend fun getUserCollections(
        @Header("Authorization") authHeader: String,
        @Path("id") accountId: String,
        @Query("page") page: Int = 1
    ): Response<List<CollectionItem>>
}

fun JsonElement?.toSafeString(): String? {
    if (this == null || this.isJsonNull) return null
    return try {
        if (this.isJsonPrimitive) {
            val prim = this.asJsonPrimitive
            if (prim.isString) prim.asString else prim.toString()
        } else {
            this.toString()
        }
    } catch (t: Throwable) {
        try {
            this.toString()
        } catch (t2: Throwable) {
            null
        }
    }
}

data class MediaResponse(
    @SerializedName("id") val id: JsonElement? = null,
    @SerializedName("type") val type: JsonElement? = null,
    @SerializedName("url") val url: JsonElement? = null,
    @SerializedName("preview_url") val previewUrl: JsonElement? = null
) {
    fun getIdString(): String? = id.toSafeString()
}

/**
 * One entry of `CollectionService::getCollection`, the payload both collection read endpoints
 * return: snowflake `id`/`pid` as strings, `post_count` (not `posts_count`) and a null
 * `published_at` for an unpublished draft. The picker only needs [id] and [title]; the rest mirrors
 * the server instead of guessing keys the API never sends (`name`, `posts_count`).
 */
data class CollectionItem(
    @SerializedName("id") val id: JsonElement? = null,
    @SerializedName("pid") val profileId: JsonElement? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("visibility") val visibility: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("thumb") val thumb: String? = null,
    @SerializedName("post_count") val postCount: Int? = null,
    @SerializedName("published_at") val publishedAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
) {
    fun getIdString(): String? = id.toSafeString()
    fun getDisplayName(): String = title?.takeIf { it.isNotBlank() } ?: "Untitled Collection"
}

data class AccountResponse(
    @SerializedName("id") val id: JsonElement? = null
) {
    fun getIdString(): String? = id.toSafeString()
}

data class TagItem(
    @SerializedName("name") val name: String? = null
)

/**
 * `api/v2/search?type=hashtags`. Gson instantiates via `Unsafe`, so a Kotlin default never runs:
 * an omitted `hashtags` key would arrive as `null` through a non-null type. It is nullable here so
 * the boundary is explicit and callers handle "no hashtags key" deliberately (DATA-10).
 */
data class HashtagSearchResponse(
    @SerializedName("hashtags") val hashtags: List<TagItem>? = null
)

data class MediaAttachment(
    @SerializedName("id") val id: JsonElement? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("preview_url") val previewUrl: String? = null,
    @SerializedName("description") val description: String? = null
)

data class StatusItem(
    @SerializedName("id") val id: JsonElement? = null,
    @SerializedName("tags") val tags: List<TagItem>? = null,
    @SerializedName("content") val content: String? = null,
    @SerializedName("text") val text: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("spoiler_text") val spoilerText: String? = null,
    @SerializedName("media_attachments") val mediaAttachments: List<MediaAttachment>? = null,
    @SerializedName("favourites_count") val favouritesCount: Int = 0,
    @SerializedName("reblogs_count") val reblogsCount: Int = 0
) {
    fun getIdString(): String? = id.toSafeString()
}

data class PlaceItem(
    @SerializedName("id") val id: JsonElement? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lng") val lng: Double? = null
) {
    fun getIdString(): String? = id.toSafeString()
    fun getDisplayName(): String {
        val n = name?.takeIf { it.isNotBlank() } ?: "Unknown Place"
        val c = country?.takeIf { it.isNotBlank() }
        return if (c != null) "$n, $c" else n
    }
}

data class StatusResponse(
    @SerializedName("id") val id: JsonElement? = null,
    @SerializedName("url") val url: JsonElement? = null
) {
    fun getIdString(): String? = id.toSafeString()
}
