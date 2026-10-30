package ovh.litapp.pixlit.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ovh.litapp.pixlit.data.Fetch
import ovh.litapp.pixlit.data.NotLoggedInException
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.data.api.PixelfedApiFactory
import ovh.litapp.pixlit.data.api.PlaceItem
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.api.StatusResponse
import ovh.litapp.pixlit.data.asPixelfedHttpException
import ovh.litapp.pixlit.data.auth.OAuthParsers
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.data.media.MediaUploader
import ovh.litapp.pixlit.data.media.PhotoUploadStep
import ovh.litapp.pixlit.data.media.PreparedImage
import ovh.litapp.pixlit.data.session.SessionRepository
import ovh.litapp.pixlit.data.status.StatusCache
import ovh.litapp.pixlit.data.status.StatusRemoteSource
import ovh.litapp.pixlit.data.tags.TagAggregator
import ovh.litapp.pixlit.data.tags.TagSearchCache
import java.io.IOException
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

    suspend fun exchangeCodeForToken(
        code: String,
        redirectUri: String,
        codeVerifier: String? = null
    ): Result<String> = sessionRepository.exchangeCodeForToken(code, redirectUri, codeVerifier)

    /**
     * The upload seam the worker calls (UP-05). The ViewModel no longer uploads: it enqueues an
     * `UploadJob` and observes it. [idempotencyKey] is that job's id, so a retried post is
     * deduplicated by the server, and [alreadyUploaded] carries the media ids a previous attempt
     * already uploaded so they are not sent twice.
     */
    suspend fun uploadPhotosAndCreateStatus(
        imageUris: List<Uri>,
        caption: String,
        resizeTo8Mb: Boolean = false,
        collectionIds: List<String> = emptyList(),
        placeId: String? = null,
        altTexts: Map<Uri, String> = emptyMap(),
        idempotencyKey: String,
        alreadyUploaded: List<String?> = emptyList(),
        onPhotoUploaded: suspend (PhotoUploadStep) -> Unit = {}
    ): Result<StatusResponse> = mediaUploader.uploadPhotosAndCreateStatus(
        imageUris = imageUris,
        caption = caption,
        resizeTo8Mb = resizeTo8Mb,
        collectionIds = collectionIds,
        placeId = placeId,
        altTexts = altTexts,
        idempotencyKey = idempotencyKey,
        alreadyUploaded = alreadyUploaded,
        onPhotoUploaded = onPhotoUploaded
    )

    /**
     * The one prepared file for ([uri], [resize]) (UP-08), shared by the preview and the upload so
     * the photo is decoded and encoded once. The worker uploads and deletes it; a preview that is
     * never uploaded leaves it to the upload cache sweep (UP-07).
     */
    suspend fun prepareImage(uri: Uri, resize: Boolean): Result<PreparedImage> =
        mediaUploader.prepareImage(uri, resize)

    suspend fun searchPlaces(
        query: String? = null,
        lat: Double? = null,
        lng: Double? = null
    ): Fetch<List<PlaceItem>> = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl ?: return@withContext Fetch.Failed(NotLoggedInException())
        val accessToken = tokenManager.accessToken ?: return@withContext Fetch.Failed(NotLoggedInException())
        try {
            val api = apiFactory.get(instanceUrl)
            val response = withTimeoutOrNull(8_000L) {
                api.searchPlaces(
                    authHeader = "Bearer $accessToken",
                    query = query,
                    lat = lat,
                    lng = lng
                )
            } ?: return@withContext Fetch.Failed(IOException("Place search timed out"))
            val places = response.body()
            if (response.isSuccessful && places != null) {
                // An empty list here is a real answer: the query genuinely matched no place.
                Fetch.Fresh(places)
            } else {
                Fetch.Failed(response.asPixelfedHttpException("GET api/v1/places/search"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "searchPlaces failed", e)
            Fetch.Failed(e)
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

    fun getDefaultStaticTags(): List<String> {
        return TagAggregator.extractTopTagsFromStatuses(memoizedStaticStatuses, topCount = TagAggregator.MAX_DISPLAYED_TAGS)
    }

    suspend fun getDefaultStaticTagCounts(): List<TagCount> = withContext(Dispatchers.Default) {
        TagAggregator.extractTopTagCountsFromStatuses(memoizedStaticStatuses, topCount = TagAggregator.MAX_DISPLAYED_TAGS)
    }

    /**
     * The account's collections, newest first. An empty list is a real answer ("you have none"),
     * not a reason to fabricate placeholders (DATA-05 / UP-13): the previous static fallback
     * produced `local_…` ids that 404ed silently on assignment while the UI reported the collection
     * as added.
     *
     * Read from `collections/accounts/{id}` (the `accounts/{id}/collections` path the app used was a
     * 404). The endpoint paginates at 9 and returns a bare array, so pages are read until a short
     * one arrives; otherwise a user with more than nine collections silently lost the rest.
     */
    suspend fun getUserCollections(): Fetch<List<CollectionItem>> = withContext(Dispatchers.IO) {
        val instanceUrl = tokenManager.instanceUrl
        val accessToken = tokenManager.accessToken

        if (instanceUrl.isNullOrBlank() || accessToken.isNullOrBlank()) {
            return@withContext Fetch.Failed(NotLoggedInException())
        }

        try {
            val api = apiFactory.get(instanceUrl)
            val accountId = statusRemoteSource.requireAccountId(api, instanceUrl, accessToken)
                .getOrElse { return@withContext Fetch.Failed(it) }
            val collections = mutableListOf<CollectionItem>()
            var page = 1
            while (page <= MAX_COLLECTION_PAGES) {
                val endpoint = "GET api/pixelfed/v1/collections/accounts/$accountId?page=$page"
                val response = api.getUserCollections("Bearer $accessToken", accountId, page)
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    return@withContext Fetch.Failed(response.asPixelfedHttpException(endpoint))
                }
                collections += body
                if (body.size < COLLECTIONS_PAGE_SIZE) break
                page++
            }
            Fetch.Fresh(collections)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getUserCollections failed", e)
            Fetch.Failed(e)
        }
    }

    /**
     * Tags and recent posts, typed so the UI can tell "you have no posts" from "the read broke"
     * (DATA-05 / 04 F-11). Room data served while the refresh failed is [Fetch.Cached]; the bundled
     * asset is no longer substituted here, the ViewModel owns its own onboarding fallback.
     */
    suspend fun getUserTopTagsAndPosts(forceRefresh: Boolean = false): Fetch<TagsAndPosts> =
        withContext(Dispatchers.IO) {
            val instanceUrl = tokenManager.instanceUrl ?: return@withContext Fetch.Failed(NotLoggedInException())
            val accessToken = tokenManager.accessToken

            val statuses = if (accessToken.isNullOrBlank()) {
                val cached = readCachedStatuses(instanceUrl)
                if (cached.isEmpty()) return@withContext Fetch.Failed(NotLoggedInException())
                Fetch.Fresh(cached)
            } else {
                statusRemoteSource.loadStatuses(instanceUrl, accessToken, forceRefresh)
            }

            statuses.map { statusList ->
                TagsAndPosts(
                    topTags = TagAggregator.extractTopTagCountsFromStatuses(
                        statusList,
                        topCount = TagAggregator.MAX_DISPLAYED_TAGS
                    ),
                    statuses = statusList
                )
            }
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

            // `hashtags` is nullable on the DTO because Gson can inject null when the v2 response
            // omits the key (DATA-10): a missing collection is "no results", never an NPE.
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

        /** `CollectionController` hardcodes `paginate(9)` on the collection read endpoints. */
        private const val COLLECTIONS_PAGE_SIZE = 9

        /** A guard against a misbehaving paginator returning a full page forever. */
        private const val MAX_COLLECTION_PAGES = 10

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
