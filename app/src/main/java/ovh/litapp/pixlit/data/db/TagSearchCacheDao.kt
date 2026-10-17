package ovh.litapp.pixlit.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TagSearchCacheDao {
    /** The cached payload for [key], or null when absent or fetched at or before [minFetchedAt]. */
    @Query("SELECT namesJson FROM tag_search_cache WHERE cacheKey = :key AND fetchedAt > :minFetchedAt")
    suspend fun get(key: String, minFetchedAt: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: TagSearchCacheEntity)

    @Query("DELETE FROM tag_search_cache WHERE fetchedAt <= :minFetchedAt")
    suspend fun pruneExpired(minFetchedAt: Long)

    @Query("SELECT COUNT(*) FROM tag_search_cache")
    suspend fun count(): Int

    @Query("DELETE FROM tag_search_cache")
    suspend fun clear()
}
