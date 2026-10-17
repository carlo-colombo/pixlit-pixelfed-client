package ovh.litapp.pixlit.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import ovh.litapp.pixlit.data.api.MediaAttachment
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.api.TagItem
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Cached status row.
 *
 * [createdAt] is the status's own server `created_at` as epoch millis and is the DAO's ordering key;
 * [fetchedAt] is only when this row was written. [tagsJson] and [mediaAttachmentsJson] carry the two
 * tag sources the live aggregator reads, so a cached read ranks tags exactly like a live fetch.
 */
@Entity(tableName = "statuses")
data class StatusEntity(
    @PrimaryKey val id: String,
    val content: String?,
    val text: String?,
    val description: String?,
    val createdAt: Long,
    val fetchedAt: Long,
    val instanceUrl: String,
    /** `List<TagItem>` from `status.tags`. */
    val tagsJson: String?,
    /** `List<MediaAttachment>` from `status.media_attachments`, descriptions included. */
    val mediaAttachmentsJson: String?
)

private val tagListType = object : TypeToken<List<TagItem>>() {}.type
private val mediaAttachmentListType = object : TypeToken<List<MediaAttachment>>() {}.type

/**
 * Reads a Pixelfed `created_at` (`2024-05-01T12:00:00.000000Z`, or an offset form). Returns null for
 * an absent or unparseable value, so the caller can fall back to the fetch time instead of inventing
 * a timestamp.
 */
private fun parseStatusCreatedAt(raw: String?): Long? = raw
    ?.takeIf { it.isNotBlank() }
    ?.let { value ->
        runCatching {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }

/** Null when the status carries no id: the DAO's primary key cannot be synthesized. */
fun StatusItem.toEntity(instanceUrl: String, fetchedAt: Long, gson: Gson): StatusEntity? {
    val statusId = getIdString() ?: return null
    return StatusEntity(
        id = statusId,
        content = content,
        text = text,
        description = description,
        createdAt = parseStatusCreatedAt(createdAt) ?: fetchedAt,
        fetchedAt = fetchedAt,
        instanceUrl = instanceUrl,
        tagsJson = tags?.let { gson.toJson(it, tagListType) },
        mediaAttachmentsJson = mediaAttachments?.let { gson.toJson(it, mediaAttachmentListType) }
    )
}

/**
 * Rebuilds exactly the fields a cached read feeds to the tag aggregator and the recent-posts UI. The
 * server's `created_at` string is not preserved verbatim; the cached epoch is rendered back as an
 * ISO-8601 instant.
 */
fun StatusEntity.toItem(gson: Gson): StatusItem = StatusItem(
    id = JsonPrimitive(id),
    tags = tagsJson?.let { runCatching { gson.fromJson<List<TagItem>>(it, tagListType) }.getOrNull() },
    content = content,
    text = text,
    description = description,
    createdAt = Instant.ofEpochMilli(createdAt).toString(),
    mediaAttachments = mediaAttachmentsJson?.let {
        runCatching { gson.fromJson<List<MediaAttachment>>(it, mediaAttachmentListType) }.getOrNull()
    }
)
