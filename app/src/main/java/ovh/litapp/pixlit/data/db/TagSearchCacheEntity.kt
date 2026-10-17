package ovh.litapp.pixlit.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached hashtag-search result, replacing the SharedPreferences JSON blob (DATA-08 / F-17).
 *
 * [cacheKey] is `"$instanceUrl|$query"` so the same query against another instance is a distinct
 * row. [fetchedAt] is when the server answered and backs the TTL predicate in
 * [TagSearchCacheDao.get]; expired rows are evicted on write, so the table stays bounded.
 */
@Entity(tableName = "tag_search_cache")
data class TagSearchCacheEntity(
    @PrimaryKey val cacheKey: String,
    val namesJson: String,
    val fetchedAt: Long
)
