package ovh.litapp.pixlit.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.data.api.PixelfedApi
import ovh.litapp.pixlit.data.api.StatusResponse
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.api.toSafeString
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.utils.ImageUtils
import ovh.litapp.pixlit.data.db.AppDatabase
import ovh.litapp.pixlit.data.db.StatusDao
import ovh.litapp.pixlit.data.db.toEntity
import ovh.litapp.pixlit.data.db.toItem
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class TagCount(val name: String, val count: Int)

data class CacheSummaryItem(val name: String, val entityCount: Int)

private data class CachedTagSearch(
    val names: List<String>
)

/**
 * Memoizes the `verify_credentials` account id for one instance+token pair. A different instance or a
 * new token (logout, re-login) misses the entry, so the id is re-resolved instead of surviving a
 * session change.
 */
internal class AccountIdCache {
    private data class Entry(val instanceUrl: String, val accessToken: String, val accountId: String)

    private val entry = AtomicReference<Entry?>(null)

    fun get(instanceUrl: String, accessToken: String): String? = entry.get()
        ?.takeIf { it.instanceUrl == instanceUrl && it.accessToken == accessToken }
        ?.accountId

    fun put(instanceUrl: String, accessToken: String, accountId: String) {
        entry.set(Entry(instanceUrl, accessToken, accountId))
    }
}

private const val MAX_DISPLAYED_TAGS = 30

@Singleton
class PixelfedRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val statusDao: StatusDao
) {

    private val accountIdCache = AccountIdCache()
    private val accountIdMutex = Mutex()

    /** Serializes status refreshes so concurrent callers share a single fetch. */
    private val refreshMutex = Mutex()
    private var lastRefreshAtMs = 0L
    private var lastRefreshInstanceUrl: String? = null

    private fun getRetrofit(baseUrl: String): Retrofit {
        val sanitizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        return Retrofit.Builder()
            .baseUrl(sanitizedUrl)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(okHttpClient)
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
            Log.e(TAG, "Network/Registration failed", e)
            val causeMessage = e.localizedMessage ?: e.message ?: e.toString()
            return@withContext Result.failure(Exception("Registration failed: $causeMessage"))
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
            Log.e(TAG, "OAuth token exchange failed", e)
            val causeMessage = e.localizedMessage ?: e.message ?: e.toString()
            return@withContext Result.failure(Exception("Token exchange failed: $causeMessage"))
        }
    }

    suspend fun uploadPhotoAndCreateStatus(
        imageUri: Uri,
        caption: String,
        resizeTo8Mb: Boolean = false,
        collectionIds: List<String> = emptyList(),
        placeId: String? = null
    ): Result<StatusResponse> {
        return uploadPhotosAndCreateStatus(listOf(imageUri), caption, resizeTo8Mb, collectionIds, placeId)
    }

    suspend fun uploadPhotosAndCreateStatus(
        imageUris: List<Uri>,
        caption: String,
        resizeTo8Mb: Boolean = false,
        collectionIds: List<String> = emptyList(),
        placeId: String? = null
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
                mediaIds = mediaIds,
                placeId = placeId
            )

            if (statusResponse.isSuccessful && statusResponse.body() != null) {
                val createdStatus = statusResponse.body()!!
                val statusId = createdStatus.getIdString()
                if (!statusId.isNullOrBlank() && collectionIds.isNotEmpty()) {
                    for (collectionId in collectionIds) {
                        try {
                            api.addStatusToCollection(
                                authHeader = "Bearer $accessToken",
                                collectionId = collectionId,
                                statusId = statusId
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to add status $statusId to collection $collectionId", e)
                        }
                    }
                }
                Result.success(createdStatus)
            } else {
                Result.failure(Exception("Status creation failed: ${statusResponse.code()} ${statusResponse.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchPlaces(
        query: String? = null,
        lat: Double? = null,
        lng: Double? = null
    ): Result<List<ovh.litapp.pixlit.data.api.PlaceItem>> = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl ?: "https://pixelfed.social"
        val accessToken = tokenManager.accessToken
        try {
            val api = getRetrofit(instanceUrl).create(PixelfedApi::class.java)
            val authHeader = accessToken?.let { "Bearer $it" }
            val response = withTimeoutOrNull(8_000L) {
                api.searchPlaces(
                    authHeader = authHeader,
                    query = query,
                    lat = lat,
                    lng = lng
                )
            } ?: return@withContext Result.failure(Exception("Place search timed out"))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchPlaces failed", e)
            Result.failure(e)
        }
    }

    data class TagsAndPosts(
        val topTags: List<TagCount>,
        val statuses: List<StatusItem>
    )

    /**
     * `assets/pixelfed-statuses.json` (~1 MB), read and parsed at most once per process with the
     * injected [gson]. Synchronized because the first access can come from either [Dispatchers.Default]
     * or [Dispatchers.IO] depending on which path touches it first.
     */
    private val memoizedStaticStatuses: List<StatusItem> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            context.assets.open(STATIC_STATUSES_ASSET).use { input ->
                gson.fromJson<List<StatusItem>>(input.reader(), STATIC_STATUS_LIST_TYPE)
            } ?: emptyList()
        }.getOrElse { error ->
            Log.e(TAG, "getStaticStatuses: Failed to read/parse $STATIC_STATUSES_ASSET", error)
            emptyList()
        }
    }

    /** The memoized asset contents; see [memoizedStaticStatuses] for the threading constraint. */
    fun getStaticStatuses(): List<StatusItem> = memoizedStaticStatuses

    fun getDefaultStaticTags(): List<String> {
        return extractTopTagsFromStatuses(memoizedStaticStatuses, topCount = MAX_DISPLAYED_TAGS)
    }

    suspend fun getDefaultStaticTagCounts(): List<TagCount> = withContext(Dispatchers.Default) {
        extractTopTagCountsFromStatuses(memoizedStaticStatuses, topCount = MAX_DISPLAYED_TAGS)
    }

    fun getDefaultStaticCollections(): List<CollectionItem> {
        return listOf(
            CollectionItem(id = com.google.gson.JsonPrimitive("static_1"), title = "Portfolio"),
            CollectionItem(id = com.google.gson.JsonPrimitive("static_2"), title = "Travel"),
            CollectionItem(id = com.google.gson.JsonPrimitive("static_3"), title = "Favorites"),
            CollectionItem(id = com.google.gson.JsonPrimitive("static_4"), title = "Art & Design")
        )
    }

    suspend fun getUserCollections(): Result<List<CollectionItem>> = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl
        val accessToken = tokenManager.accessToken

        if (instanceUrl.isNullOrBlank() || accessToken.isNullOrBlank()) {
            return@withContext Result.success(getDefaultStaticCollections())
        }

        try {
            val api = getRetrofit(instanceUrl).create(PixelfedApi::class.java)
            val authHeader = "Bearer $accessToken"
            val accountId = requireAccountId(api, instanceUrl, accessToken).getOrNull()
            if (accountId != null) {
                val collectionsResp = api.getUserCollections(authHeader, accountId)
                if (collectionsResp.isSuccessful && collectionsResp.body() != null) {
                    val collections = collectionsResp.body()!!
                    return@withContext Result.success(if (collections.isNotEmpty()) collections else getDefaultStaticCollections())
                }
            }
            Result.success(getDefaultStaticCollections())
        } catch (e: Exception) {
            Log.e(TAG, "getUserCollections failed, falling back to static collections", e)
            Result.success(getDefaultStaticCollections())
        }
    }

    suspend fun createCollection(
        title: String,
        description: String? = null,
        visibility: String = "public"
    ): Result<CollectionItem> = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl
        val accessToken = tokenManager.accessToken

        if (instanceUrl.isNullOrBlank() || accessToken.isNullOrBlank()) {
            val localId = "local_${System.currentTimeMillis()}"
            val newItem = CollectionItem(
                id = com.google.gson.JsonPrimitive(localId),
                title = title,
                description = description,
                visibility = visibility
            )
            return@withContext Result.success(newItem)
        }

        try {
            val api = getRetrofit(instanceUrl).create(PixelfedApi::class.java)
            val authHeader = "Bearer $accessToken"
            val response = api.createCollection(authHeader, title, description, visibility)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val localId = "local_${System.currentTimeMillis()}"
                Result.success(CollectionItem(
                    id = com.google.gson.JsonPrimitive(localId),
                    title = title,
                    description = description,
                    visibility = visibility
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createCollection failed, creating local fallback collection", e)
            val localId = "local_${System.currentTimeMillis()}"
            Result.success(CollectionItem(
                id = com.google.gson.JsonPrimitive(localId),
                title = title,
                description = description,
                visibility = visibility
            ))
        }
    }

    suspend fun getUserTopTagsAndPosts(forceRefresh: Boolean = false): Result<TagsAndPosts> = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl
            ?: return@withContext Result.failure(Exception("Not logged in"))
        val accessToken = tokenManager.accessToken

        val finalStatuses = if (accessToken.isNullOrBlank()) {
            readCachedStatuses(instanceUrl)
        } else {
            loadStatuses(instanceUrl, accessToken, forceRefresh)
        }

        val topTags = extractTopTagCountsFromStatuses(finalStatuses, topCount = MAX_DISPLAYED_TAGS)
        Result.success(TagsAndPosts(topTags = topTags, statuses = finalStatuses))
    }

    /**
     * Single-flight read-through for the status list. Only a forced call or an expired
     * [STATUS_REFRESH_TTL_MS] reaches the network; everyone else waits on [refreshMutex] and then
     * serves Room/bundled data. [lastRefreshAtMs] advances on a failed attempt too, so a burst of
     * callers does not retry a broken endpoint — a forced refresh (pull-to-refresh) always does.
     */
    private suspend fun loadStatuses(
        instanceUrl: String,
        accessToken: String,
        forceRefresh: Boolean
    ): List<StatusItem> = refreshMutex.withLock {
        if (lastRefreshInstanceUrl != instanceUrl) {
            lastRefreshInstanceUrl = instanceUrl
            lastRefreshAtMs = 0L
        }
        if (!shouldRefreshStatuses(forceRefresh, lastRefreshAtMs, System.currentTimeMillis())) {
            return@withLock readCachedStatuses(instanceUrl)
        }
        lastRefreshAtMs = System.currentTimeMillis()
        fetchFromApi(instanceUrl, accessToken).getOrElse { error ->
            Log.w(TAG, "getUserTopTagsAndPosts: API fetch failed, using cached statuses", error)
            readCachedStatuses(instanceUrl)
        }
    }

    /** Room first, then the bundled asset; both reads stay off the main thread. */
    private suspend fun readCachedStatuses(instanceUrl: String): List<StatusItem> {
        val cached = try {
            statusDao.getStatuses(instanceUrl).map { it.toItem(gson) }
        } catch (e: Exception) {
            Log.e(TAG, "getUserTopTagsAndPosts: Room read failed", e)
            emptyList()
        }
        if (cached.isNotEmpty()) return cached
        Log.d(TAG, "getUserTopTagsAndPosts: status cache empty, using static statuses")
        return getStaticStatuses()
    }

    /**
     * The account id behind `verify_credentials`, resolved once per instance+token instead of once
     * per call/page. The mutex keeps two concurrent bootstrap callers from both resolving it.
     */
    private suspend fun requireAccountId(
        api: PixelfedApi,
        instanceUrl: String,
        accessToken: String
    ): Result<String> {
        accountIdCache.get(instanceUrl, accessToken)?.let { return Result.success(it) }
        return accountIdMutex.withLock {
            accountIdCache.get(instanceUrl, accessToken)?.let { return@withLock Result.success(it) }
            val verified = try {
                api.verifyCredentials("Bearer $accessToken")
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }
            if (!verified.isSuccessful) {
                return@withLock Result.failure(Exception("Failed to verify credentials: ${verified.code()}"))
            }
            val accountId = verified.body()?.getIdString()?.takeIf { it.isNotBlank() }
                ?: return@withLock Result.failure(Exception("Failed to get account ID"))
            accountIdCache.put(instanceUrl, accessToken, accountId)
            Result.success(accountId)
        }
    }

    private suspend fun persistStatuses(statuses: List<StatusItem>, instanceUrl: String) {
        val fetchedAt = System.currentTimeMillis()
        val entities = statuses.mapNotNull { it.toEntity(instanceUrl, fetchedAt, gson) }
        if (entities.isNotEmpty()) statusDao.insertStatuses(entities)
    }

    private suspend fun fetchFromApi(instanceUrl: String, accessToken: String): Result<List<StatusItem>> {
        val api = getRetrofit(instanceUrl).create(PixelfedApi::class.java)
        val accountId = requireAccountId(api, instanceUrl, accessToken).getOrElse { return Result.failure(it) }
        return try {
            val statusesResponse = api.getUserStatuses("Bearer $accessToken", accountId, limit = 40)
            if (!statusesResponse.isSuccessful) {
                return Result.failure(Exception("Failed to fetch statuses: ${statusesResponse.code()}"))
            }
            val statuses = statusesResponse.body() ?: emptyList()
            if (statuses.isEmpty()) {
                return Result.failure(Exception("API returned no statuses"))
            }
            persistStatuses(statuses, instanceUrl)
            Result.success(statuses)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMoreUserStatuses(maxId: String): Result<List<StatusItem>> = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl
        val accessToken = tokenManager.accessToken
        if (instanceUrl.isNullOrBlank() || accessToken.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Not logged in"))
        }

        try {
            val api = getRetrofit(instanceUrl).create(PixelfedApi::class.java)
            val accountId = requireAccountId(api, instanceUrl, accessToken)
                .getOrElse { return@withContext Result.failure(it) }
            val response = api.getUserStatuses("Bearer $accessToken", accountId, limit = 40, maxId = maxId)
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to fetch more statuses: ${response.code()}"))
            }
            val statuses = response.body().orEmpty()
            persistStatuses(statuses, instanceUrl)
            Result.success(statuses)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserTopTags(forceRefresh: Boolean = false): Result<List<String>> {
        return getUserTopTagsAndPosts(forceRefresh).map { result -> result.topTags.map { it.name } }
    }

    suspend fun searchTags(query: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().removePrefix("#").lowercase()
        if (normalizedQuery.isBlank()) return@withContext Result.success(emptyList())

        val instanceUrl = tokenManager.instanceUrl
        val cacheKey = "${instanceUrl.orEmpty()}|$normalizedQuery"
        val cacheType = object : TypeToken<MutableMap<String, CachedTagSearch>>() {}.type
        val cache = runCatching {
            gson.fromJson<MutableMap<String, CachedTagSearch>>(
                tokenManager.tagSearchCacheJson,
                cacheType
            ) ?: mutableMapOf()
        }.getOrElse { mutableMapOf() }
        val cached = cache[cacheKey]
        if (cached != null) {
            return@withContext Result.success(cached.names)
        }

        if (instanceUrl.isNullOrBlank()) return@withContext Result.failure(Exception("Not logged in"))

        try {
            val response = getRetrofit(instanceUrl).create(PixelfedApi::class.java)
                .searchHashtags(
                    authHeader = tokenManager.accessToken?.let { "Bearer $it" },
                    query = normalizedQuery
                )
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Tag search failed: ${response.code()}"))
            }

            val names = response.body()?.hashtags.orEmpty()
                .mapNotNull { it.name?.trim()?.removePrefix("#")?.takeIf(String::isNotBlank) }
                .distinctBy(String::lowercase)
            cache[cacheKey] = CachedTagSearch(names)
            tokenManager.tagSearchCacheJson = gson.toJson(cache, cacheType)
            Result.success(names)
        } catch (e: Exception) {
            Log.e(TAG, "searchTags failed", e)
            Result.failure(e)
        }
    }

    suspend fun getCacheSummary(): List<CacheSummaryItem> = withContext(Dispatchers.IO) {
        val tagSearchCount = runCatching {
            val type = object : TypeToken<Map<String, CachedTagSearch>>() {}.type
            gson.fromJson<Map<String, CachedTagSearch>>(tokenManager.tagSearchCacheJson, type)?.size ?: 0
        }.getOrDefault(0)
        val legacyTagCount = runCatching {
            val json = tokenManager.cachedTagsJson
            if (json.isNullOrBlank()) 0 else {
                val element = com.google.gson.JsonParser.parseString(json)
                if (element.isJsonArray) element.asJsonArray.size() else 0
            }
        }.getOrDefault(0)
        val statusCount = runCatching {
            tokenManager.instanceUrl?.let { statusDao.getStatuses(it).size } ?: 0
        }.getOrDefault(0)

        listOf(
            CacheSummaryItem("Remote tag searches", tagSearchCount),
            CacheSummaryItem("Legacy cached tags", legacyTagCount),
            CacheSummaryItem("Cached statuses", statusCount)
        )
    }

    companion object {
        private const val TAG = "PixelfedRepository"
        private const val STATIC_STATUSES_ASSET = "pixelfed-statuses.json"

        /** How long a status refresh is considered current; a forced refresh ignores it. */
        const val STATUS_REFRESH_TTL_MS = 5 * 60_000L

        private val STATIC_STATUS_LIST_TYPE = object : TypeToken<List<StatusItem>>() {}.type

        /** The refresh decision for [getUserTopTagsAndPosts]; `lastRefreshAtMs` of 0 always refreshes. */
        fun shouldRefreshStatuses(
            forceRefresh: Boolean,
            lastRefreshAtMs: Long,
            nowMs: Long,
            ttlMs: Long = STATUS_REFRESH_TTL_MS
        ): Boolean = forceRefresh || nowMs - lastRefreshAtMs >= ttlMs

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
            return extractTopTagCountsFromStatuses(statuses, topCount, staticTags)
                .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.name })
                .map { it.name }
        }

        fun extractTopTagCountsFromStatuses(
            statuses: List<StatusItem>,
            topCount: Int = MAX_DISPLAYED_TAGS,
            staticTags: List<String> = emptyList()
        ): List<TagCount> {
            val tagCounts = mutableMapOf<String, Int>()

            val sanitizedStaticParam = staticTags.mapNotNull { tag ->
                val s = tag.trim().removePrefix("#").lowercase()
                if (s.isNotEmpty()) s else null
            }.distinct()

            sanitizedStaticParam.forEach { tag ->
                tagCounts[tag] = 0
            }

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

            // Select the most-used tags before alphabetizing the displayed result.
            return tagCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(topCount)
                .sortedBy { it.key }
                .map { TagCount(it.key, it.value) }
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
