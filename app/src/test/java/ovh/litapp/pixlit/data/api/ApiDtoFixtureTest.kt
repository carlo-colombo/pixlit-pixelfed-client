package ovh.litapp.pixlit.data.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.di.NetworkModule

/**
 * Pins the response contract the app depends on by parsing recorded server payloads with the
 * production [Gson] configuration (DATA-10 / 04 F-18). Each assertion names a field the repository,
 * the tag aggregator or the pickers actually read, so a response-shape change fails here instead of
 * degrading silently into "no results" on the device.
 */
class ApiDtoFixtureTest {

    private val gson: Gson = NetworkModule.provideGson()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "missing fixture $name" }
            .readText()

    @Test
    fun `verify_credentials fixture yields the numeric account id`() {
        val account = gson.fromJson(fixture("verify_credentials.json"), AccountResponse::class.java)

        // requireAccountId() keys every status/collection call on this value.
        assertEquals("987654321", account.getIdString())
    }

    @Test
    fun `statuses fixture keeps tags, media descriptions and created_at`() {
        val statuses = gson.fromJson(fixture("statuses.json"), Array<StatusItem>::class.java).toList()

        assertEquals(3, statuses.size)
        val first = statuses[0]
        assertEquals("109847263019283746", first.getIdString())
        assertEquals(listOf("harbour", "landscape"), first.tags?.map { it.name })
        assertEquals(
            listOf("Fishing boats moored at dusk #design"),
            first.mediaAttachments?.map { it.description }
        )
        assertEquals("2024-05-01T18:22:31.000000Z", first.createdAt)
        assertEquals(12, first.favouritesCount)

        // A status with no `tags`/`media_attachments` keys stays absent rather than fabricated.
        assertNull(statuses[2].tags)
        assertNull(statuses[2].mediaAttachments)
        // An id that arrives as a JSON number is still readable as a string.
        assertEquals("109847263019283748", statuses[2].getIdString())
    }

    @Test
    fun `statuses fixture media descriptions reach the tag aggregator`() {
        val statuses = gson.fromJson(fixture("statuses.json"), Array<StatusItem>::class.java).toList()

        // The aggregator counts media descriptions as a tag source (DATA-04): `#design` appears in
        // exactly two of the three fixture descriptions.
        val counts = PixelfedRepository.extractTopTagCountsFromStatuses(statuses, topCount = 20)

        assertEquals(2, counts.first { it.name == "design" }.count)
    }

    @Test
    fun `media fixture yields the upload id the status creation depends on`() {
        val media = gson.fromJson(fixture("media.json"), MediaResponse::class.java)

        assertEquals("555000113", media.getIdString())
        assertEquals("image", media.type?.asString)
        assertEquals("https://pixelfed.example/storage/media/555000113/upload.jpg", media.url?.asString)
    }

    @Test
    fun `search_v2 fixture exposes the hashtags the tag cloud ranks`() {
        val response = gson.fromJson(fixture("search_v2.json"), HashtagSearchResponse::class.java)

        assertEquals(
            listOf("photography", "landscape", "nightphotography"),
            response.hashtags?.map { it.name }
        )
    }

    @Test
    fun `places_search fixture keeps ids, coordinates and display-name fallbacks`() {
        val places = gson.fromJson(fixture("places_search.json"), Array<PlaceItem>::class.java).toList()

        assertEquals(3, places.size)
        assertEquals("3682", places[0].getIdString())
        assertEquals(42.424662, places[0].lat!!, 1e-9)
        assertEquals("Kotor, Montenegro", places[0].getDisplayName())
        // A blank country is dropped rather than rendered as "Lofoten, ".
        assertEquals("Lofoten", places[1].getDisplayName())
        // A place without a name still renders something addressable, and keeps its coordinates null.
        assertEquals("Unknown Place, Iceland", places[2].getDisplayName())
        assertNull(places[2].lat)
    }

    @Test
    fun `collections fixture keeps ids, counts and title-to-name fallback`() {
        val collections =
            gson.fromJson(fixture("collections.json"), Array<CollectionItem>::class.java).toList()

        assertEquals(3, collections.size)
        assertEquals("c0ffee12-3456-4abc-9def-0123456789ab", collections[0].getIdString())
        assertEquals(12, collections[0].postsCount)
        assertEquals("Darkroom 2024", collections[0].getDisplayName())
        // Pixelfed has answered with `name` instead of `title`; the picker must not show a blank row.
        assertEquals("Untitled drafts", collections[1].getDisplayName())
        assertEquals("Untitled Collection", collections[2].getDisplayName())
        assertNull(collections[2].postsCount)
    }
}
