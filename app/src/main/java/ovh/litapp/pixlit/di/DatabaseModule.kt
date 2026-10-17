package ovh.litapp.pixlit.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ovh.litapp.pixlit.data.db.AppDatabase
import ovh.litapp.pixlit.data.db.StatusDao
import ovh.litapp.pixlit.data.db.TagSearchCacheDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "pixlit_db"
        )
            // Real 2 -> 3 migration for the index and the tag-search cache (DATA-11 / DATA-08). The
            // statuses/tag tables are disposable caches, so an install older than version 2 is still
            // recreated rather than migrated.
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideStatusDao(database: AppDatabase): StatusDao = database.statusDao()

    @Provides
    @Singleton
    fun provideTagSearchCacheDao(database: AppDatabase): TagSearchCacheDao = database.tagSearchCacheDao()
}
