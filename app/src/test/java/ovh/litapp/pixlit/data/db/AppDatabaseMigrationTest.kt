package ovh.litapp.pixlit.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `AppDatabase.MIGRATION_2_3` against a real v2 database file (TEST-03).
 *
 * Room validates the migrated schema against the generated one before it opens, so this test fails
 * both if the migration SQL drifts from the entity definitions and if the index/table it creates
 * cannot actually be queried.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `migrating v2 to v3 keeps rows and adds the index and the tag search cache table`() {
        createVersion2Database()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            val rows = runBlocking { database.statusDao().getStatuses(INSTANCE) }
            assertEquals(listOf("id-2", "id-1"), rows.map { it.id })

            val cache = database.tagSearchCacheDao()
            runBlocking {
                cache.put(TagSearchCacheEntity(cacheKey = "k", namesJson = "[\"tag\"]", fetchedAt = 1L))
            }
            assertEquals(1, runBlocking { cache.count() })

            assertTrue(indexNames(database).contains(StatusInstanceIndex))
        } finally {
            database.close()
        }
    }

    /** A v2 database: the version-3 `statuses` columns without the index and without the new table. */
    private fun createVersion2Database() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            legacy.execSQL(
                "CREATE TABLE IF NOT EXISTS `statuses` (`id` TEXT NOT NULL, `content` TEXT, " +
                    "`text` TEXT, `description` TEXT, `createdAt` INTEGER NOT NULL, " +
                    "`fetchedAt` INTEGER NOT NULL, `instanceUrl` TEXT NOT NULL, `tagsJson` TEXT, " +
                    "`mediaAttachmentsJson` TEXT, PRIMARY KEY(`id`))"
            )
            legacy.execSQL(
                "INSERT INTO `statuses` VALUES ('id-1','older',NULL,NULL,1700000000000,1700000000001,'$INSTANCE',NULL,NULL)"
            )
            legacy.execSQL(
                "INSERT INTO `statuses` VALUES ('id-2','newer',NULL,NULL,1700000002000,1700000002001,'$INSTANCE',NULL,NULL)"
            )
            legacy.version = 2
        } finally {
            legacy.close()
        }
    }

    private fun indexNames(database: AppDatabase): List<String> =
        database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'index'")
            .use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
            }

    private companion object {
        const val DB_NAME = "migration-v2-v3.db"
        const val INSTANCE = "https://pixelfed.example"
        const val StatusInstanceIndex = "index_statuses_instanceUrl_createdAt"
    }
}
