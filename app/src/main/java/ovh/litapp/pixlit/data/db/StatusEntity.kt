package ovh.litapp.pixlit.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "statuses")
data class StatusEntity(
    @PrimaryKey val id: String,
    val content: String?,
    val text: String?,
    val description: String?,
    val createdAt: Long,
    val instanceUrl: String
)
