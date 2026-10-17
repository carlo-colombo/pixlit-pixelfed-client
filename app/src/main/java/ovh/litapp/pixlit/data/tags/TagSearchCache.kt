package ovh.litapp.pixlit.data.tags

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ovh.litapp.pixlit.data.db.AppDatabase
import ovh.litapp.pixlit.data.db.TagSearchCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed hashtag-search cache with a TTL (DATA-08 / F-17). It replaces the SharedPreferences
 * JSON blob that was re-serialised in full on every search: a read is a single indexed lookup and a
 * write is one REPLACE plus a stale-row purge in one transaction, so interleaved searches can no
 * longer lose entries, the table stays bounded, and the preferences file holding the access token
 * is never rewritten by a search.
 */
@Singleton
class TagSearchCache @Inject constructor(
    private val database: AppDatabase,
    private val gson: Gson
) {
    private val dao = database.tagSearchCacheDao()

    /** The cached names for [query], or null when absent or older than [TTL_MS]. */
    suspend fun get(instanceUrl: String?, query: String): List<String>? {
        val json = dao.get(cacheKey(instanceUrl, query), System.currentTimeMillis() - TTL_MS) ?: return null
        return runCatching { gson.fromJson<List<String>>(json, namesType) }.getOrNull()
    }

    suspend fun put(instanceUrl: String?, query: String, names: List<String>) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.put(TagSearchCacheEntity(cacheKey(instanceUrl, query), gson.toJson(names, namesType), now))
            dao.pruneExpired(now - TTL_MS)
        }
    }

    suspend fun count(): Int = dao.count()

    suspend fun clear() = dao.clear()

    companion object {
        /** Hashtag names change slowly; a day keeps search responsive without serving stale data. */
        const val TTL_MS = 24L * 60 * 60 * 1000

        fun cacheKey(instanceUrl: String?, query: String): String = "${instanceUrl.orEmpty()}|$query"

        private val namesType = object : TypeToken<List<String>>() {}.type
    }
}
