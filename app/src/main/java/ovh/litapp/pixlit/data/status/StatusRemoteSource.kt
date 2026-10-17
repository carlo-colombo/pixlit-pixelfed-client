package ovh.litapp.pixlit.data.status

import android.util.Log
import ovh.litapp.pixlit.data.api.PixelfedApi
import ovh.litapp.pixlit.data.api.PixelfedApiFactory
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.repository.AccountIdCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network source of the user's statuses plus the single-flight refresh strategy (DATA-06 step 5 /
 * F-12). Reads its cache through [StatusCache] and never touches the UI or SharedPreferences.
 */
@Singleton
class StatusRemoteSource @Inject constructor(
    private val apiFactory: PixelfedApiFactory,
    private val statusCache: StatusCache
) {

    private val accountIdCache = AccountIdCache()
    private val accountIdMutex = Mutex()

    /** Serializes status refreshes so concurrent callers share a single fetch. */
    private val refreshMutex = Mutex()
    private var lastRefreshAtMs = 0L
    private var lastRefreshInstanceUrl: String? = null

    /**
     * Single-flight read-through for the status list. Only a forced call or an expired
     * [STATUS_REFRESH_TTL_MS] reaches the network; everyone else waits on [refreshMutex] and then
     * serves Room data. [lastRefreshAtMs] advances on a failed attempt too, so a burst of callers
     * does not retry a broken endpoint — a forced refresh (pull-to-refresh) always does.
     *
     * Returns an empty list when neither the network nor Room has data; the facade decides the
     * bundled-asset fallback.
     */
    suspend fun loadStatuses(
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
            Log.w(TAG, "loadStatuses: API fetch failed, using cached statuses", error)
            readCachedStatuses(instanceUrl)
        }
    }

    suspend fun moreStatuses(
        instanceUrl: String,
        accessToken: String,
        maxId: String
    ): Result<List<StatusItem>> = withContext(Dispatchers.IO) {
        try {
            val api = apiFactory.get(instanceUrl)
            val accountId = requireAccountId(api, instanceUrl, accessToken)
                .getOrElse { return@withContext Result.failure(it) }
            val response = api.getUserStatuses("Bearer $accessToken", accountId, limit = 40, maxId = maxId)
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to fetch more statuses: ${response.code()}"))
            }
            val statuses = response.body().orEmpty()
            statusCache.persistStatuses(statuses, instanceUrl)
            Result.success(statuses)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The account id behind `verify_credentials`, resolved once per instance+token instead of once
     * per call/page. The mutex keeps two concurrent bootstrap callers from both resolving it.
     */
    suspend fun requireAccountId(
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

    private suspend fun readCachedStatuses(instanceUrl: String): List<StatusItem> = try {
        statusCache.readStatuses(instanceUrl)
    } catch (e: Exception) {
        Log.e(TAG, "loadStatuses: Room read failed", e)
        emptyList()
    }

    private suspend fun fetchFromApi(instanceUrl: String, accessToken: String): Result<List<StatusItem>> {
        val api = apiFactory.get(instanceUrl)
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
            statusCache.persistStatuses(statuses, instanceUrl)
            Result.success(statuses)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "StatusRemoteSource"

        /** How long a status refresh is considered current; a forced refresh ignores it. */
        const val STATUS_REFRESH_TTL_MS = 5 * 60_000L

        /** The refresh decision for [loadStatuses]; `lastRefreshAtMs` of 0 always refreshes. */
        fun shouldRefreshStatuses(
            forceRefresh: Boolean,
            lastRefreshAtMs: Long,
            nowMs: Long,
            ttlMs: Long = STATUS_REFRESH_TTL_MS
        ): Boolean = forceRefresh || nowMs - lastRefreshAtMs >= ttlMs
    }
}
