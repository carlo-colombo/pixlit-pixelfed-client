package ovh.litapp.pixlit.data.tags

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ovh.litapp.pixlit.data.db.AppDatabase
import ovh.litapp.pixlit.data.db.TagSearchCacheEntity

@RunWith(AndroidJUnit4::class)
class TagSearchCacheTest {

    private lateinit var database: AppDatabase
    private lateinit var cache: TagSearchCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = TagSearchCache(database, Gson())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a stored search round-trips`() = runTest {
        cache.put(INSTANCE, "photo", listOf("photo", "photography"))

        assertEquals(listOf("photo", "photography"), cache.get(INSTANCE, "photo"))
    }

    @Test
    fun `the cache is scoped per instance`() = runTest {
        cache.put(INSTANCE, "photo", listOf("photo"))

        assertNull(cache.get("https://other.example", "photo"))
    }

    @Test
    fun `entries older than the ttl are ignored and evicted on the next write`() = runTest {
        val dao = database.tagSearchCacheDao()
        dao.put(
            TagSearchCacheEntity(
                cacheKey = TagSearchCache.cacheKey(INSTANCE, "photo"),
                namesJson = "[\"stale\"]",
                fetchedAt = System.currentTimeMillis() - TagSearchCache.TTL_MS - 1
            )
        )

        assertNull(cache.get(INSTANCE, "photo"))

        cache.put(INSTANCE, "new", listOf("new"))
        assertEquals(1, dao.count())
    }

    private companion object {
        const val INSTANCE = "https://pixelfed.example"
    }
}
