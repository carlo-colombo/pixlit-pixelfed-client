package ovh.litapp.pixlit.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.data.api.PixelfedApiFactory
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.api.StatusResponse
import ovh.litapp.pixlit.data.auth.OAuthParsers
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.data.media.MediaUploader
import ovh.litapp.pixlit.data.session.SessionRepository
import ovh.litapp.pixlit.data.status.StatusCache
import ovh.litapp.pixlit.data.status.StatusRemoteSource
import ovh.litapp.pixlit.data.tags.TagAggregator
import ovh.litapp.pixlit.data.tags.TagSearchCache
import javax.inject.Inject
import javax.inject.Singleton

data class TagCount(val name: String, val count: Int)

data class CacheSummaryItem(val name: String, val entityCount: Int)

/**
 * The facade the ViewModels inject. It owns no behaviour beyond the bundled static data; OAuth
 * (DATA-06 step 3), media upload (step 4), status network/cache (step 5) and the per-instance API
 * (step 6) live in their own seams under `data/`.
 */
@Singleton
class PixelfedRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager,
    private val gson: Gson,
    private val apiFactory: PixelfedApiFactory,
    private val sessionRepository: SessionRepository,
    private val mediaUploader: MediaUploader,
    private val statusCache: StatusCache,
    private val statusRemoteSource: StatusRemoteSource,
    private val tagSearchCache: TagSearchCache
) {

    suspend fun registerApp(instanceUrl: String, redirectUri: String): Result<Pair<String, String>> =
        sessionRepository.registerApp(instanceUrl, redirectUri)

    suspend fun exchangeCodeForToken(code: String, redirectUri: String): Result<String> =
        sessionRepository.exchangeCodeForToken(code, redirectUri)

    suspend fun uploadPhotoAndCreateStatus(
        imageUri: Uri,
        caption: String,
        resizeTo8Mb: Boolean = false,
        collectionIds: List<String> = emptyList(),
        placeId: String? = null
    ): Result<StatusResponse> {
        return mediaUploader.uploadPhotoAndCreateStatus(imageUri, caption, resizeTo8Mb, collectionIds, placeId)
    }

    suspend fun uploadPhotosAndCreateStatus(
        imageUris: List<Uri>,
        caption: String,
        resizeTo8Mb: Boolean = false,
        collectionIds: List<String> = emptyList(),
        placeId: String? = null
    ): Result<StatusResponse> {
        return mediaUploader.uploadPhotosAndCreateStatus(imageUris, caption, resizeTo8Mb, collectionIds, placeId)
    }

    suspend fun searchPlaces(
        query: String? = null,
        lat: Double? = null,
        lng: Double? = null
    ): Result<List<ovh.litapp.pixlit.data.api.PlaceItem>> = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl
            ?: return@withContext Result.failure(Exception("Not logged in"))
        val accessToken = tokenManager.accessToken
            ?: return@withContext Result.failure(Exception("Not logged in"))
        try {
            val api = apiFactory.get(instanceUrl)
            val response = withTimeoutOrNull(8_000L) {
                api.searchPlaces(
                    authHeader = "Bearer $accessToken",
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
        return TagAggregator.extractTopTagsFromStatuses(memoizedStaticStatuses, topCount = TagAggregator.MAX_DISPLAYED_TAGS)
    }

    suspend fun getDefaultStaticTagCounts(): List<TagCount> = withContext(Dispatchers.Default) {
        TagAggregator.extractTopTagCountsFromStatuses(memoizedStaticStatuses, topCount = TagAggregator.MAX_DISPLAYED_TAGS)
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
            val api = apiFactory.get(instanceUrl)
            val authHeader = "Bearer $accessToken"
            val accountId = statusRemoteSource.requireAccountId(api, instanceUrl, accessToken).getOrNull()
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
            return@withContext Result.success(localCollection(title, description, visibility))
        }

        try {
            val api = apiFactory.get(instanceUrl)
            val authHeader = "Bearer $accessToken"
            val response = api.createCollection(authHeader, title, description, visibility)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(localCollection(title, description, visibility))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createCollection failed, creating local fallback collection", e)
            Result.success(localCollection(title, description, visibility))
        }
    }

    private fun localCollection(title: String, description: String?, visibility: String): CollectionItem {
        val localId = "local_${System.currentTimeMillis()}"
        return CollectionItem(
            id = com.google.gson.JsonPrimitive(localId),
            title = title,
            description = description,
            visibility = visibility
        )
    }

    suspend fun getUserTopTagsAndPosts(forceRefresh: Boolean = false): Result<TagsAndPosts> = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl
            ?: return@withContext Result.failure(Exception("Not logged in"))
        val accessToken = tokenManager.accessToken

        val statuses = (
            if (accessToken.isNullOrBlank()) {
                readCachedStatuses(instanceUrl)
            } else {
                statusRemoteSource.loadStatuses(instanceUrl, accessToken, forceRefresh)
            }
            ).ifEmpty { getStaticStatuses() }

        val topTags = TagAggregator.extractTopTagCountsFromStatuses(statuses, topCount = TagAggregator.MAX_DISPLAYED_TAGS)
        Result.success(TagsAndPosts(topTags = topTags, statuses = statuses))
    }

    suspend fun getMoreUserStatuses(maxId: String): Result<List<StatusItem>> {
        val instanceUrl = tokenManager.instanceUrl
        val accessToken = tokenManager.accessToken
        if (instanceUrl.isNullOrBlank() || accessToken.isNullOrBlank()) {
            return Result.failure(Exception("Not logged in"))
        }
        return statusRemoteSource.moreStatuses(instanceUrl, accessToken, maxId)
    }

    suspend fun searchTags(query: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().removePrefix("#").lowercase()
        if (normalizedQuery.isBlank()) return@withContext Result.success(emptyList())

        val instanceUrl = tokenManager.instanceUrl
        tagSearchCache.get(instanceUrl, normalizedQuery)?.let { return@withContext Result.success(it) }

        if (instanceUrl.isNullOrBlank()) return@withContext Result.failure(Exception("Not logged in"))

        try {
            val response = apiFactory.get(instanceUrl)
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
            tagSearchCache.put(instanceUrl, normalizedQuery, names)
            Result.success(names)
        } catch (e: Exception) {
            Log.e(TAG, "searchTags failed", e)
            Result.failure(e)
        }
    }

    suspend fun getCacheSummary(): List<CacheSummaryItem> = withContext(Dispatchers.IO) {
        val tagSearchCount = runCatching { tagSearchCache.count() }.getOrDefault(0)
        val statusCount = runCatching {
            tokenManager.instanceUrl?.let { statusCache.countStatuses(it) } ?: 0
        }.getOrDefault(0)

        listOf(
            CacheSummaryItem("Remote tag searches", tagSearchCount),
            CacheSummaryItem("Cached statuses", statusCount)
        )
    }

    /**
     * Purges the current instance's cached statuses and the tag-search cache. Exposed for the
     * logout path; the caller (`MainViewModel.logout()`, outside the data lane) must invoke it
     * before `tokenManager.clear()`, since the instance URL is read from the token store here, and
     * must launch it off the main thread because it is `suspend`.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl
        if (!instanceUrl.isNullOrBlank()) {
            runCatching { statusCache.clearStatuses(instanceUrl) }
                .onFailure { Log.e(TAG, "clearCache: failed to purge cached statuses", it) }
        }
        runCatching { tagSearchCache.clear() }
            .onFailure { Log.e(TAG, "clearCache: failed to purge the tag-search cache", it) }
    }

    /** Room first; the bundled asset is only a fallback, applied by [getUserTopTagsAndPosts]. */
    private suspend fun readCachedStatuses(instanceUrl: String): List<StatusItem> = try {
        statusCache.readStatuses(instanceUrl)
    } catch (e: Exception) {
        Log.e(TAG, "readCachedStatuses: Room read failed", e)
        emptyList()
    }

    companion object {
        private const val TAG = "PixelfedRepository"
        private const val STATIC_STATUSES_ASSET = "pixelfed-statuses.json"

        /** How long a status refresh is considered current; a forced refresh ignores it. */
        const val STATUS_REFRESH_TTL_MS = StatusRemoteSource.STATUS_REFRESH_TTL_MS

        private val STATIC_STATUS_LIST_TYPE = object : TypeToken<List<StatusItem>>() {}.type

        /**
         * Compatibility delegates: the pure helpers now live in [TagAggregator] and [OAuthParsers]
         * (DATA-06 steps 1–2), and the refresh decision in [StatusRemoteSource]. These forwarders
         * keep existing callers — including the JVM tests — source-compatible.
         */
        fun shouldRefreshStatuses(
            forceRefresh: Boolean,
            lastRefreshAtMs: Long,
            nowMs: Long,
            ttlMs: Long = STATUS_REFRESH_TTL_MS
        ): Boolean = StatusRemoteSource.shouldRefreshStatuses(forceRefresh, lastRefreshAtMs, nowMs, ttlMs)

        fun extractTagsFromPostsText(text: String): List<String> =
            TagAggregator.extractTagsFromPostsText(text)

        fun parseTokenResponseBody(rawBody: String): String? =
            OAuthParsers.parseTokenResponseBody(rawBody)

        fun parseRegistrationResponseBody(rawBody: String): Pair<String?, String?> =
            OAuthParsers.parseRegistrationResponseBody(rawBody)

        fun parseErrorResponseBody(rawErrBody: String): String =
            OAuthParsers.parseErrorResponseBody(rawErrBody)

        fun extractTopTagsFromStatuses(
            statuses: List<StatusItem>,
            topCount: Int = 20,
            staticTags: List<String> = emptyList()
        ): List<String> = TagAggregator.extractTopTagsFromStatuses(statuses, topCount, staticTags)

        fun extractTopTagCountsFromStatuses(
            statuses: List<StatusItem>,
            topCount: Int = TagAggregator.MAX_DISPLAYED_TAGS,
            staticTags: List<String> = emptyList()
        ): List<TagCount> = TagAggregator.extractTopTagCountsFromStatuses(statuses, topCount, staticTags)
    }
}
