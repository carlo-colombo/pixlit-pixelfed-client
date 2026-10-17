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
            // The statuses table is a disposable cache: on a schema change, recreate it instead of
            // migrating rows. Room 2.8 only offers the argument form (`dropAllTables`), so the call
            // is positional: true drops every table in this single-table database.
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    fun provideStatusDao(database: AppDatabase): StatusDao = database.statusDao()
}
