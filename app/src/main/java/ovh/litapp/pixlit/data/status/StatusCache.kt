package ovh.litapp.pixlit.data.status

import com.google.gson.Gson
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.db.StatusDao
import ovh.litapp.pixlit.data.db.toEntity
import ovh.litapp.pixlit.data.db.toItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room read/write + DTO mapping for the status cache (DATA-06 step 5 / F-12). Holds no network or
 * session logic so the cache is independently testable with an in-memory DAO.
 */
@Singleton
class StatusCache @Inject constructor(
    private val statusDao: StatusDao,
    private val gson: Gson
) {

    suspend fun readStatuses(instanceUrl: String): List<StatusItem> =
        statusDao.getStatuses(instanceUrl).map { it.toItem(gson) }

    suspend fun persistStatuses(statuses: List<StatusItem>, instanceUrl: String) {
        val fetchedAt = System.currentTimeMillis()
        val entities = statuses.mapNotNull { it.toEntity(instanceUrl, fetchedAt, gson) }
        if (entities.isNotEmpty()) statusDao.insertStatuses(entities)
    }

    suspend fun countStatuses(instanceUrl: String): Int = statusDao.countStatuses(instanceUrl)
}
