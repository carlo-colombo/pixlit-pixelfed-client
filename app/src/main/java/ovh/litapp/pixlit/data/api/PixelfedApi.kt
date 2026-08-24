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
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface PixelfedApi {

    @GET("api/v1/accounts/verify_credentials")
    suspend fun verifyCredentials(
        @Header("Authorization") authHeader: String
    ): Response<AccountResponse>

    @GET("api/v1/accounts/{id}/statuses")
    suspend fun getUserStatuses(
        @Header("Authorization") authHeader: String,
        @Path("id") accountId: String,
        @Query("limit") limit: Int = 20
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
        @Field("scope") scope: String
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
        @Field("status") status: String,
        @Field("media_ids[]") mediaIds: List<String>
    ): Response<StatusResponse>
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

data class RegisterAppResponse(
    @SerializedName("id") val id: JsonElement? = null,
    @SerializedName("client_id") val clientId: JsonElement? = null,
    @SerializedName("client_secret") val clientSecret: JsonElement? = null
) {
    fun getClientIdString(): String? = clientId.toSafeString()
    fun getClientSecretString(): String? = clientSecret.toSafeString()
}

data class TokenResponse(
    @SerializedName("access_token") val accessToken: JsonElement? = null,
    @SerializedName("token_type") val tokenType: JsonElement? = null,
    @SerializedName("scope") val scope: JsonElement? = null,
    @SerializedName("created_at") val createdAt: JsonElement? = null
) {
    fun getAccessTokenString(): String? = accessToken.toSafeString()
}

data class MediaResponse(
    @SerializedName("id") val id: JsonElement? = null,
    @SerializedName("type") val type: JsonElement? = null,
    @SerializedName("url") val url: JsonElement? = null,
    @SerializedName("preview_url") val previewUrl: JsonElement? = null
) {
    fun getIdString(): String? = id.toSafeString()
}

data class AccountResponse(
    @SerializedName("id") val id: JsonElement? = null
) {
    fun getIdString(): String? = id.toSafeString()
}

data class TagItem(
    @SerializedName("name") val name: String? = null
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
    @SerializedName("spoiler_text") val spoilerText: String? = null,
    @SerializedName("media_attachments") val mediaAttachments: List<MediaAttachment>? = null
) {
    fun getIdString(): String? = id.toSafeString()
}

data class StatusResponse(
    @SerializedName("id") val id: JsonElement? = null,
    @SerializedName("url") val url: JsonElement? = null
) {
    fun getIdString(): String? = id.toSafeString()
}
