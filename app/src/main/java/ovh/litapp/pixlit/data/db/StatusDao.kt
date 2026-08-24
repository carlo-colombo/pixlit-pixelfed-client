package ovh.litapp.pixlit.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatusDao {
    @Query("SELECT * FROM statuses WHERE instanceUrl = :instanceUrl ORDER BY createdAt DESC")
    fun getStatusesFlow(instanceUrl: String): Flow<List<StatusEntity>>

    @Query("SELECT * FROM statuses WHERE instanceUrl = :instanceUrl ORDER BY createdAt DESC")
    suspend fun getStatuses(instanceUrl: String): List<StatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatuses(statuses: List<StatusEntity>)

    @Query("DELETE FROM statuses WHERE instanceUrl = :instanceUrl")
    suspend fun clearStatuses(instanceUrl: String)
}
