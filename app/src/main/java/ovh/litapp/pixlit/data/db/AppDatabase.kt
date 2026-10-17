package ovh.litapp.pixlit.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Version 3 (DATA-11 / DATA-08): indexes the statuses filter+sort by (instanceUrl, createdAt) and
// adds the Room-backed tag_search_cache table. Schema export is on (REL-12), so every later change
// must bump the version and ship a migration.
@Database(
    entities = [StatusEntity::class, TagSearchCacheEntity::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun statusDao(): StatusDao
    abstract fun tagSearchCacheDao(): TagSearchCacheDao

    companion object {
        /**
         * Version 2 → 3. A manual migration is used because `exportSchema` was off at version 2, so
         * there is no committed baseline schema for an `@AutoMigration` to diff against. The SQL
         * matches exactly what Room would generate for the new index and table, including Room's
         * index name, so the post-migration schema validation passes.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_statuses_instanceUrl_createdAt` " +
                        "ON `statuses` (`instanceUrl`, `createdAt`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tag_search_cache` " +
                        "(`cacheKey` TEXT NOT NULL, `namesJson` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))"
                )
            }
        }
    }
}
