package ovh.litapp.pixlit.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import ovh.litapp.pixlit.data.api.PixelfedApi
import ovh.litapp.pixlit.data.api.StatusResponse
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.api.toSafeString
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.utils.ImageUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream

class PixelfedRepository(private val context: Context, private val tokenManager: TokenManager) {

    private fun getRetrofit(baseUrl: String): Retrofit {
        val sanitizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val gson = GsonBuilder()
            .setLenient()
            .create()
        return Retrofit.Builder()
            .baseUrl(sanitizedUrl)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(OkHttpClient.Builder().build())
            .build()
    }

    suspend fun registerApp(instanceUrl: String, redirectUri: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val cachedInstance = tokenManager.instanceUrl
            val cachedClientId = tokenManager.clientId
            val cachedClientSecret = tokenManager.clientSecret

            if (cachedInstance.equals(instanceUrl, ignoreCase = true) &&
                !cachedClientId.isNullOrBlank() &&
                !cachedClientSecret.isNullOrBlank()) {
                return@withContext Result.success(Pair(cachedClientId, cachedClientSecret))
            }

            val api = getRetrofit(instanceUrl).create(PixelfedApi::class.java)
            val response = api.registerApp(
                clientName = "Pixlit Android Client",
                redirectUris = redirectUri,
                scopes = "read write follow",
                website = "https://pixelfed.org"
            )
            if (response.isSuccessful && response.body() != null) {
                val rawBodyString = response.body()!!.string()
                val (clientId, clientSecret) = parseRegistrationResponseBody(rawBodyString)
                if (!clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()) {
                    tokenManager.clientId = clientId
                    tokenManager.clientSecret = clientSecret
                    tokenManager.instanceUrl = instanceUrl
                    return@withContext Result.success(Pair(clientId, clientSecret))
                } else {
                    val preview = if (rawBodyString.length > 200) rawBodyString.take(200) + "..." else rawBodyString
                    return@withContext Result.failure(Exception("Registration response missing client_id or client_secret ($preview)"))
                }
            } else {
                val rawErrBody = response.errorBody()?.string()?.trim()
                val parsedMsg = if (!rawErrBody.isNullOrEmpty()) {
                    parseErrorResponseBody(rawErrBody)
                } else {
                    response.message().ifBlank { "HTTP ${response.code()}" }
                }

                val fullError = "Registration error (HTTP ${response.code()}): $parsedMsg"
                return@withContext Result.failure(Exception(fullError))
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val stackTraceString = sw.toString()
            val causeMessage = e.localizedMessage ?: e.message ?: e.toString()
            val errorMsg = "Network/Registration failed (${e.javaClass.name}): $causeMessage\n\nStacktrace:\n$stackTraceString"
            return@withContext Result.failure(Exception(errorMsg, e))
        }
    }

    suspend fun exchangeCodeForToken(code: String, redirectUri: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val instanceUrl = tokenManager.instanceUrl
            val clientId = tokenManager.clientId
            val clientSecret = tokenManager.clientSecret

            if (instanceUrl.isNullOrBlank() || clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
                val missing = mutableListOf<String>()
                if (instanceUrl.isNullOrBlank()) missing.add("instanceUrl")
                if (clientId.isNullOrBlank()) missing.add("clientId")
                if (clientSecret.isNullOrBlank()) missing.add("clientSecret")
                return@withContext Result.failure(Exception("Missing OAuth credentials required for token exchange: ${missing.joinToString()}"))
            }

            val api = getRetrofit(instanceUrl).create(PixelfedApi::class.java)
            val response = api.fetchAccessToken(
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = redirectUri,
                code = code,
                scope = "read write follow"
            )

            if (response.isSuccessful && response.body() != null) {
                val rawBodyString = response.body()!!.string()
                val accessToken = parseTokenResponseBody(rawBodyString)
                if (!accessToken.isNullOrBlank()) {
                    tokenManager.accessToken = accessToken
                    return@withContext Result.success(accessToken)
                } else {
                    val preview = if (rawBodyString.length > 200) rawBodyString.take(200) + "..." else rawBodyString
                    return@withContext Result.failure(Exception("Access token missing in OAuth response ($preview)"))
                }
            } else {
                val rawErrBody = response.errorBody()?.string()?.trim()
                val parsedMsg = if (!rawErrBody.isNullOrEmpty()) {
                    parseErrorResponseBody(rawErrBody)
                } else {
                    response.message().ifBlank { "HTTP ${response.code()}" }
                }

                val fullError = "OAuth Token error (HTTP ${response.code()}): $parsedMsg"
                return@withContext Result.failure(Exception(fullError))
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val stackTraceString = sw.toString()
            val causeMessage = e.localizedMessage ?: e.message ?: e.toString()
            val errorMsg = "OAuth token exchange failed (${e.javaClass.name}): $causeMessage\n\nStacktrace:\n$stackTraceString"
            return@withContext Result.failure(Exception(errorMsg, e))
        }
    }

    suspend fun uploadPhotoAndCreateStatus(
        imageUri: Uri,
        caption: String,
        resizeTo8Mb: Boolean = false
    ): Result<StatusResponse> {
        return uploadPhotosAndCreateStatus(listOf(imageUri), caption, resizeTo8Mb)
    }

    suspend fun uploadPhotosAndCreateStatus(
        imageUris: List<Uri>,
        caption: String,
        resizeTo8Mb: Boolean = false
    ): Result<StatusResponse> = withContext(Dispatchers.IO) {
        if (imageUris.isEmpty()) {
            return@withContext Result.failure(Exception("No images selected for upload"))
        }

        try {
            val instanceUrl = tokenManager.instanceUrl ?: return@withContext Result.failure(Exception("Not logged in"))
            val accessToken = tokenManager.accessToken ?: return@withContext Result.failure(Exception("Not logged in"))

            val api = getRetrofit(instanceUrl).create(PixelfedApi::class.java)
            val mediaIds = mutableListOf<String>()

            for (uri in imageUris) {
                val file = if (resizeTo8Mb) {
                    ImageUtils.resizeImageDownToMaxBytes(context, uri, ImageUtils.MAX_BYTES_8MB)
                } else {
                    getFileFromUri(uri)
                } ?: return@withContext Result.failure(Exception("Unable to process image file"))

                val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
                val multipartBody = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val descRequestBody = caption.toRequestBody("text/plain".toMediaTypeOrNull())

                val mediaResponse = api.uploadMedia(
                    authHeader = "Bearer $accessToken",
                    file = multipartBody,
                    description = descRequestBody
                )

                if (!mediaResponse.isSuccessful || mediaResponse.body() == null) {
                    return@withContext Result.failure(Exception("Media upload failed: ${mediaResponse.code()} ${mediaResponse.errorBody()?.string()}"))
                }

                val mediaId = mediaResponse.body()!!.getIdString()
                    ?: return@withContext Result.failure(Exception("Media upload response missing ID"))

                mediaIds.add(mediaId)
            }

            val statusResponse = api.createStatus(
                authHeader = "Bearer $accessToken",
                status = caption,
                mediaIds = mediaIds
            )

            if (statusResponse.isSuccessful && statusResponse.body() != null) {
                Result.success(statusResponse.body()!!)
            } else {
                Result.failure(Exception("Status creation failed: ${statusResponse.code()} ${statusResponse.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class TagsAndPosts(
        val topTags: List<String>,
        val statuses: List<StatusItem>
    )

    fun getStaticStatuses(): List<StatusItem> {
        val jsonString = try {
            context.assets.open("pixelfed-statuses.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "getStaticStatuses: Failed to read pixelfed-statuses.json from assets", e)
            return emptyList()
        }

        return try {
            val listType = object : TypeToken<List<StatusItem>>() {}.type
            Gson().fromJson(jsonString, listType) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getStaticStatuses: Failed to parse pixelfed-statuses.json", e)
            emptyList()
        }
    }

    fun getDefaultStaticTags(): List<String> {
        val statuses = getStaticStatuses()
        return extractTopTagsFromStatuses(statuses, topCount = 20)
    }

    suspend fun getUserTopTagsAndPosts(forceRefresh: Boolean = false): Result<TagsAndPosts> = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl
        val accessToken = tokenManager.accessToken

        val apiResult = if (!instanceUrl.isNullOrBlank() && !accessToken.isNullOrBlank()) {
            try {
                val api = getRetrofit(instanceUrl).create(PixelfedApi::class.java)
                val authHeader = "Bearer $accessToken"
                
                val verifyResponse = api.verifyCredentials(authHeader)
                if (verifyResponse.isSuccessful) {
                    val accountId = verifyResponse.body()?.getIdString()
                    if (accountId != null) {
                        val statusesResponse = api.getUserStatuses(authHeader, accountId, limit = 40)
                        if (statusesResponse.isSuccessful) {
                            val statuses = statusesResponse.body() ?: emptyList()
                            if (statuses.isNotEmpty()) {
                                Result.success(statuses)
                            } else {
                                Result.failure(Exception("API returned no statuses"))
                            }
                        } else {
                            Result.failure(Exception("Failed to fetch statuses: ${statusesResponse.code()}"))
                        }
                    } else {
                        Result.failure(Exception("Failed to get account ID"))
                    }
                } else {
                    Result.failure(Exception("Failed to verify credentials: ${verifyResponse.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(Exception("Not logged in"))
        }

        val finalStatuses = apiResult.getOrElse {
            Log.d(TAG, "getUserTopTagsAndPosts: API fetch failed or empty, falling back to static statuses. Error: ${it.message}")
            getStaticStatuses()
        }

        val topTags = extractTopTagsFromStatuses(finalStatuses, topCount = 20)
        Result.success(TagsAndPosts(topTags = topTags, statuses = finalStatuses))
    }

    suspend fun getUserTopTags(forceRefresh: Boolean = false): Result<List<String>> {
        return getUserTopTagsAndPosts(forceRefresh).map { it.topTags }
    }

    companion object {
        private const val TAG = "PixelfedRepository"

        fun extractTagsFromPostsText(text: String): List<String> {
            return Regex("""#([\p{L}\p{N}_-]+)""")
                .findAll(text)
                .map { it.groupValues[1].lowercase() }
                .distinct()
                .toList()
        }

        fun parseTokenResponseBody(rawBody: String): String? {
            return try {
                val jsonElement = com.google.gson.JsonParser.parseString(rawBody)
                if (jsonElement != null && jsonElement.isJsonObject) {
                    jsonElement.asJsonObject.get("access_token").toSafeString()
                } else {
                    null
                }
            } catch (t: Throwable) {
                null
            }
        }

        fun parseRegistrationResponseBody(rawBody: String): Pair<String?, String?> {
            return try {
                val jsonElement = com.google.gson.JsonParser.parseString(rawBody)
                if (jsonElement != null && jsonElement.isJsonObject) {
                    val obj = jsonElement.asJsonObject
                    val clientId = obj.get("client_id").toSafeString()
                    val clientSecret = obj.get("client_secret").toSafeString()
                    Pair(clientId, clientSecret)
                } else {
                    Pair(null, null)
                }
            } catch (t: Throwable) {
                Pair(null, null)
            }
        }

        fun parseErrorResponseBody(rawErrBody: String): String {
            return try {
                val jsonElement = com.google.gson.JsonParser.parseString(rawErrBody)
                if (jsonElement != null && jsonElement.isJsonObject) {
                    val jsonObject = jsonElement.asJsonObject

                    val errorVal = jsonObject.get("error").toSafeString()
                    val descVal = jsonObject.get("error_description").toSafeString()
                    val msgVal = jsonObject.get("message").toSafeString()

                    when {
                        !errorVal.isNullOrBlank() && !descVal.isNullOrBlank() -> "$errorVal: $descVal"
                        !descVal.isNullOrBlank() -> descVal
                        !errorVal.isNullOrBlank() -> errorVal
                        !msgVal.isNullOrBlank() -> msgVal
                        else -> rawErrBody
                    }
                } else {
                    rawErrBody
                }
            } catch (t: Throwable) {
                rawErrBody
            }
        }

        fun extractTopTagsFromStatuses(
            statuses: List<StatusItem>,
            topCount: Int = 20,
            staticTags: List<String> = emptyList()
        ): List<String> {
            val tagCounts = mutableMapOf<String, Int>()

            val sanitizedStaticParam = staticTags.mapNotNull { tag ->
                val s = tag.trim().removePrefix("#").lowercase()
                if (s.isNotEmpty()) s else null
            }.distinct()

            for (status in statuses) {
                // 1. Static tags
                val staticInStatus = mutableListOf<String>()
                sanitizedStaticParam.forEach { tag ->
                    staticInStatus.add(tag)
                }
                status.tags?.forEach { tag ->
                    val tagName = tag.name?.trim()?.removePrefix("#")
                    if (!tagName.isNullOrEmpty()) {
                        staticInStatus.add(tagName.lowercase())
                    }
                }

                // 2. Extracted tags from post text and media attachment descriptions
                val extractedInStatus = mutableListOf<String>()
                val mediaDescriptions = status.mediaAttachments?.mapNotNull { it.description } ?: emptyList()
                val textSources = listOfNotNull(status.content, status.text, status.description, status.spoilerText) + mediaDescriptions
                val combinedText = textSources.joinToString(" ")
                if (combinedText.isNotEmpty()) {
                    val hashtagRegex = Regex("""#([\p{L}\p{N}_-]+)""")
                    hashtagRegex.findAll(combinedText).forEach { matchResult ->
                        val tag = matchResult.groupValues[1].lowercase()
                        if (tag.isNotEmpty()) {
                            extractedInStatus.add(tag)
                        }
                    }
                }

                val uniqueInStatus = (extractedInStatus + staticInStatus).distinct()

                for (tag in uniqueInStatus) {
                    tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
                }
            }

            // Get top tags by count, then sort the resulting top set alphabetically
            val topEntries = tagCounts.entries
                .sortedByDescending { it.value }
                .take(topCount)
                .sortedBy { it.key }

            return topEntries.map { "#${it.key} (${it.value})" }
        }
    }

    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tempFile = File.createTempFile("upload_", ".jpg", context.cacheDir)
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
