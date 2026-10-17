package ovh.litapp.pixlit.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StatusDao {
    @Query("SELECT * FROM statuses WHERE instanceUrl = :instanceUrl ORDER BY createdAt DESC")
    suspend fun getStatuses(instanceUrl: String): List<StatusEntity>

    @Query("SELECT COUNT(*) FROM statuses WHERE instanceUrl = :instanceUrl")
    suspend fun countStatuses(instanceUrl: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatuses(statuses: List<StatusEntity>)
}
