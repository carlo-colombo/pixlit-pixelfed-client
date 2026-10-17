package ovh.litapp.pixlit.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TagSearchCacheDao` against real in-memory SQLite (TEST-03 / 04 F-19): the TTL predicate, the
 * replace-on-key write and the expiry purge that keeps the table bounded (DATA-08).
 */
@RunWith(AndroidJUnit4::class)
class TagSearchCacheDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: TagSearchCacheDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.tagSearchCacheDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `get returns only entries fetched after the cutoff`() = runTest {
        dao.put(entry("fresh", fetchedAt = NOW))
        dao.put(entry("stale", fetchedAt = NOW - TTL - 1))

        assertEquals("[\"fresh\"]", dao.get("fresh", NOW - TTL))
        assertNull(dao.get("stale", NOW - TTL))
    }

    @Test
    fun `put replaces the cached payload for the same key`() = runTest {
        dao.put(entry("photo", namesJson = "[\"old\"]", fetchedAt = NOW - 1_000))
        dao.put(entry("photo", namesJson = "[\"new\"]", fetchedAt = NOW))

        assertEquals(1, dao.count())
        assertEquals("[\"new\"]", dao.get("photo", NOW - TTL))
    }

    @Test
    fun `pruneExpired removes only entries at or before the cutoff`() = runTest {
        dao.put(entry("expired", fetchedAt = NOW - TTL))
        dao.put(entry("fresh", fetchedAt = NOW))

        dao.pruneExpired(NOW - TTL)

        assertEquals(1, dao.count())
        assertEquals("[\"fresh\"]", dao.get("fresh", NOW - TTL))
    }

    @Test
    fun `clear empties the table`() = runTest {
        dao.put(entry("photo"))
        dao.put(entry("travel"))

        dao.clear()

        assertEquals(0, dao.count())
    }

    private fun entry(
        cacheKey: String,
        namesJson: String = "[\"$cacheKey\"]",
        fetchedAt: Long = NOW
    ) = TagSearchCacheEntity(cacheKey = cacheKey, namesJson = namesJson, fetchedAt = fetchedAt)

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val TTL = 24L * 60 * 60 * 1000
    }
}
