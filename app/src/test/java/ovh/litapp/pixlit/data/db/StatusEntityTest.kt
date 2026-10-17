package ovh.litapp.pixlit.data.db

import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ovh.litapp.pixlit.data.api.MediaAttachment
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.api.TagItem
import ovh.litapp.pixlit.data.repository.PixelfedRepository

/**
 * The Room cache must round-trip every field the tag aggregator and the recent-posts UI read, so a
 * cached read ranks tags exactly like the live fetch it replaced.
 */
class StatusEntityTest {

    private val gson = Gson()
    private val instanceUrl = "https://pixelfed.example"
    private val fetchedAt = 1_700_000_000_000L

    private val statuses = listOf(
        StatusItem(
            id = JsonPrimitive("101"),
            content = "Sunset #landscape",
            tags = listOf(TagItem("photography")),
            createdAt = "2024-05-01T12:00:00.000000Z",
            mediaAttachments = listOf(
                MediaAttachment(
                    id = JsonPrimitive("900"),
                    type = "image",
                    url = "https://pixelfed.example/900.jpg",
                    previewUrl = "https://pixelfed.example/900-preview.jpg",
                    description = "Ari at the beach #design"
                )
            )
        ),
        StatusItem(
            id = JsonPrimitive("102"),
            content = "No tags in the body",
            tags = listOf(TagItem("landscape"), TagItem("  #photography  ")),
            createdAt = "2024-05-02T12:00:00.000000Z"
        )
    )

    private fun cachedStatuses(): List<StatusItem> {
        val entities = statuses.map { it.toEntity(instanceUrl, fetchedAt, gson) ?: error("fixture needs ids") }
        return entities.map { it.toItem(gson) }
    }

    @Test
    fun testRoundTrip_cachedTagCountsEqualLiveTagCounts() {
        val liveCounts = PixelfedRepository.extractTopTagCountsFromStatuses(statuses, topCount = Int.MAX_VALUE)
        val cachedCounts = PixelfedRepository.extractTopTagCountsFromStatuses(cachedStatuses(), topCount = Int.MAX_VALUE)

        assertEquals(liveCounts, cachedCounts)
        assertTrue(
            "media-attachment descriptions must survive the cache",
            cachedCounts.any { it.name == "design" }
        )
    }

    @Test
    fun testRoundTrip_preservesTheFieldsTheRecentPostsUiReads() {
        val cached = cachedStatuses()

        assertEquals("101", cached[0].getIdString())
        assertEquals("Sunset #landscape", cached[0].content)
        assertEquals(listOf(TagItem("photography")), cached[0].tags)
        assertEquals(
            "the image pager needs the attachment url, not just the description",
            "https://pixelfed.example/900.jpg",
            cached[0].mediaAttachments?.single()?.url
        )
        assertEquals("Ari at the beach #design", cached[0].mediaAttachments?.single()?.description)
        assertEquals(null, cached[1].mediaAttachments)
    }

    @Test
    fun testToEntity_persistsTheServerCreatedAtNotTheFetchTime() {
        val entity = statuses[0].toEntity(instanceUrl, fetchedAt, gson)!!

        assertEquals(1_714_564_800_000L, entity.createdAt)
        assertEquals(fetchedAt, entity.fetchedAt)
        assertNotEquals(
            "createdAt must not be the insert time any more",
            entity.fetchedAt,
            entity.createdAt
        )
    }

    @Test
    fun testToEntity_fallsBackToFetchTimeWhenCreatedAtIsMissingOrUnparseable() {
        val noTimestamp = StatusItem(id = JsonPrimitive("201"), content = "no created_at")
        val brokenTimestamp = StatusItem(id = JsonPrimitive("202"), content = "bad", createdAt = "not-a-date")

        assertEquals(fetchedAt, noTimestamp.toEntity(instanceUrl, fetchedAt, gson)!!.createdAt)
        assertEquals(fetchedAt, brokenTimestamp.toEntity(instanceUrl, fetchedAt, gson)!!.createdAt)
    }

    @Test
    fun testToEntity_returnsNullWithoutAnId() {
        assertNull(StatusItem(content = "no id").toEntity(instanceUrl, fetchedAt, gson))
    }

    @Test
    fun testToItem_returnsNullTagsAndAttachmentsWhenTheColumnsAreEmpty() {
        val entity = StatusEntity(
            id = "300",
            content = "legacy row",
            text = null,
            description = null,
            createdAt = fetchedAt,
            fetchedAt = fetchedAt,
            instanceUrl = instanceUrl,
            tagsJson = null,
            mediaAttachmentsJson = null
        )

        val item = entity.toItem(gson)

        assertEquals("300", item.getIdString())
        assertEquals("legacy row", item.content)
        assertNull(item.tags)
        assertNull(item.mediaAttachments)
    }
}
