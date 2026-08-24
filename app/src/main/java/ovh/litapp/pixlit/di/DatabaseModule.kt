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
        ).build()
    }

    @Provides
    fun provideStatusDao(database: AppDatabase): StatusDao = database.statusDao()
}
