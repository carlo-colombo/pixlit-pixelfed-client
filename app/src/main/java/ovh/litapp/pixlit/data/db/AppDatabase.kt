package ovh.litapp.pixlit.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

// Version 2 adds StatusEntity.tagsJson/mediaAttachmentsJson and replaces the insert-time createdAt
// with the server's own timestamp plus a fetchedAt. The status cache is disposable, so the builder
// falls back to a destructive migration instead of a hand-written one while exportSchema is still
// false; REL-12 turns schema export on and takes over migration testing.
@Database(entities = [StatusEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun statusDao(): StatusDao
}
