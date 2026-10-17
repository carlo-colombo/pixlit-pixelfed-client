package ovh.litapp.pixlit.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.status.StatusCache
import java.time.Instant

/**
 * `StatusDao` against real in-memory SQLite (TEST-03 / 04 F-19). Pins the access patterns the status
 * cache depends on: instance filtering, replace-on-conflict, count, `createdAt` ordering and the
 * logout purge.
 */
@RunWith(AndroidJUnit4::class)
class StatusDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: StatusDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.statusDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert, count and filter keep instances apart`() = runTest {
        dao.insertStatuses(listOf(row("id-1", INSTANCE), row("id-2", INSTANCE), row("id-3", OTHER)))

        assertEquals(2, dao.countStatuses(INSTANCE))
        assertEquals(listOf("id-1", "id-2"), dao.getStatuses(INSTANCE).map { it.id }.sorted())
        assertEquals(1, dao.countStatuses(OTHER))
    }

    @Test
    fun `a repeated primary key replaces the row instead of duplicating it`() = runTest {
        dao.insertStatuses(listOf(row("id-1", INSTANCE, content = "first")))
        dao.insertStatuses(listOf(row("id-1", INSTANCE, content = "second")))

        assertEquals(1, dao.countStatuses(INSTANCE))
        assertEquals("second", dao.getStatuses(INSTANCE).single().content)
    }

    @Test
    fun `getStatuses orders by createdAt descending`() = runTest {
        dao.insertStatuses(
            listOf(
                row("middle", INSTANCE, createdAt = 1_700_000_001_000L),
                row("oldest", INSTANCE, createdAt = 1_700_000_000_000L),
                row("newest", INSTANCE, createdAt = 1_700_000_002_000L)
            )
        )

        assertEquals(
            listOf("newest", "middle", "oldest"),
            dao.getStatuses(INSTANCE).map { it.id }
        )
    }

    @Test
    fun `clearStatuses removes only the given instance`() = runTest {
        dao.insertStatuses(listOf(row("id-1", INSTANCE), row("id-2", OTHER)))

        dao.clearStatuses(INSTANCE)

        assertEquals(0, dao.countStatuses(INSTANCE))
        assertEquals(1, dao.countStatuses(OTHER))
    }

    @Test
    fun `the persisted createdAt is the status time, not the fetch time`() = runTest {
        val cache = StatusCache(dao, Gson())
        cache.persistStatuses(
            listOf(
                StatusItem(
                    id = JsonPrimitive("101"),
                    content = "older",
                    createdAt = "2024-05-01T12:00:00.000000Z"
                ),
                StatusItem(
                    id = JsonPrimitive("102"),
                    content = "newer",
                    createdAt = "2024-05-02T12:00:00.000000Z"
                )
            ),
            INSTANCE
        )

        val rows = dao.getStatuses(INSTANCE)

        assertEquals(listOf("102", "101"), rows.map { it.id })
        assertEquals(
            Instant.parse("2024-05-02T12:00:00Z").toEpochMilli(),
            rows.first().createdAt
        )
        // A fetch-time `createdAt` (the pre-DATA-04 bug) would make the two equal.
        assertNotEquals(rows.first().createdAt, rows.first().fetchedAt)
    }

    private fun row(
        id: String,
        instanceUrl: String,
        createdAt: Long = 1_700_000_000_000L,
        content: String? = "content-$id"
    ) = StatusEntity(
        id = id,
        content = content,
        text = null,
        description = null,
        createdAt = createdAt,
        fetchedAt = createdAt + 1,
        instanceUrl = instanceUrl,
        tagsJson = null,
        mediaAttachmentsJson = null
    )

    private companion object {
        const val INSTANCE = "https://pixelfed.example"
        const val OTHER = "https://other.example"
    }
}
