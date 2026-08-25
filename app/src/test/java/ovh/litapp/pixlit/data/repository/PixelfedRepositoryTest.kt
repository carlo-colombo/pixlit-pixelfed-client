package ovh.litapp.pixlit.data.repository

import ovh.litapp.pixlit.data.api.MediaAttachment
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.api.TagItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PixelfedRepositoryTest {

    private val sampleStaticTags = listOf("photography", "italy", "northernitaly", "blueskyartshow", "generativeart")

    @Test
    fun testExtractTopTagsFromStatuses_rankingAndLimit() {
        val statuses = listOf(
            StatusItem(content = "#photography #animalphotography #canaryislands #travelphotography Lobos"),
            StatusItem(content = "ooking chicken with #earth heat at #Lanzarote - Timanfaya park - #photography #BlueSkyArtShow #travelphotography #canaryislands"),
            StatusItem(content = "#Glass jar #BlueSkyArtShow #photography"),
            StatusItem(content = "Kotor Kitten #growing #blackandwhite #classicmono #BlueSkyArtShow #catsofpixelfed #catphotography #photography #cat"),
            StatusItem(content = "#urbangaze #wien long exposure"),
            StatusItem(content = "Shinjuku - #busy view from the top - #photography #japan #travelphotography #urbangaze #BlueSkyArtShow"),
            StatusItem(content = "Black and White of the Iseo Lake - #classicmono #photography #blackandwhite #photography-bw #italy #northernitaly")
        )

        val topTags = PixelfedRepository.extractTopTagsFromStatuses(statuses, topCount = 20)

        // photography appears in 6 statuses -> top tag
        assertEquals("photography", topTags[0])
        // blueskyartshow appears in 4 statuses -> second
        assertEquals("blueskyartshow", topTags[1])
        // travelphotography appears in 3 statuses -> third
        assertEquals("travelphotography", topTags[2])
        // Check photography-bw with hyphen is extracted
        assert(topTags.contains("photography-bw"))
    }

    @Test
    fun testExtractTopTagsFromStatuses_extractsFromMediaAttachmentDescriptions() {
        val statuses = listOf(
            StatusItem(
                content = "No tags in content",
                mediaAttachments = listOf(
                    MediaAttachment(description = "Ari, campari #design"),
                    MediaAttachment(description = "#photography #animalphotography #canaryislands #travelphotography Lobos")
                )
            )
        )

        val topTags = PixelfedRepository.extractTopTagsFromStatuses(statuses, topCount = 10)

        assertEquals(5, topTags.size)
        assert(topTags.contains("design"))
        assert(topTags.contains("photography"))
        assert(topTags.contains("animalphotography"))
        assert(topTags.contains("canaryislands"))
        assert(topTags.contains("travelphotography"))
    }

    @Test
    fun testExtractTopTagsFromStatuses_limitsTo20() {
        val statuses = (1..30).map { i ->
            StatusItem(tags = listOf(TagItem("tag$i")))
        }

        val topTags = PixelfedRepository.extractTopTagsFromStatuses(statuses, topCount = 20)

        assertEquals(20, topTags.size)
    }

    @Test
    fun testExtractTopTagCounts_selectsMostUsedThenSortsAlphabetically() {
        val statuses = listOf(
            StatusItem(content = "#zebra #apple #apple #apple"),
            StatusItem(content = "#zebra #banana #banana"),
            StatusItem(content = "#zebra #cherry"),
            StatusItem(content = "#date")
        )

        val topTags = PixelfedRepository.extractTopTagCountsFromStatuses(statuses, topCount = 3)

        assertEquals(
            listOf(
                TagCount("apple", 1),
                TagCount("zebra", 3),
                TagCount("banana", 1)
            ).sortedBy { it.name },
            topTags
        )
        assertEquals(listOf("apple", "banana", "zebra"), topTags.map { it.name })
    }

    @Test
    fun testExtractTopTagsFromStatuses_extractsAllWithoutCappingWhenUnbounded() {
        val statuses = (1..30).map { i ->
            StatusItem(tags = listOf(TagItem("tag$i")))
        }

        val allTags = PixelfedRepository.extractTopTagsFromStatuses(statuses, topCount = Int.MAX_VALUE, staticTags = sampleStaticTags)

        // 30 unique status tags + 5 sample static tags
        assertEquals(35, allTags.size)
    }

    @Test
    fun testExtractTagsFromPostsText_extractedCorrectlyFromPostsText() {
        val text = "Carlo @pictures.litapp.ovh · 1d #photography #animalphotography #canaryislands #travelphotography"
        val expectedTags = listOf("photography", "animalphotography", "canaryislands", "travelphotography")
        val extracted = PixelfedRepository.extractTagsFromPostsText(text)
        assertEquals(4, extracted.size)
        assertEquals(expectedTags, extracted)
    }

    @Test
    fun testExtractTopTagsFromStatuses_handlesEmptyAndDuplicates() {
        val statuses = listOf(
            StatusItem(tags = listOf(TagItem("  #nature  "), TagItem("NATURE"), TagItem(""))),
            StatusItem(content = "#nature #NATURE #nature")
        )

        val topTags = PixelfedRepository.extractTopTagsFromStatuses(statuses, topCount = Int.MAX_VALUE)

        assertEquals(1, topTags.size)
        assertEquals("nature", topTags[0])
    }

    @Test
    fun testExtractTopTagsFromStatuses_returnsStaticTagsWhenStatusesEmpty() {
        val statuses = emptyList<StatusItem>()

        val topTags = PixelfedRepository.extractTopTagsFromStatuses(statuses, topCount = Int.MAX_VALUE, staticTags = sampleStaticTags)

        assertEquals(sampleStaticTags.size, topTags.size)
        // Expecting alphabetical order for tags with same frequency
        assertEquals(sampleStaticTags.sorted(), topTags)
    }

    @Test
    fun testExtractTopTagsFromStatuses_alwaysIncludesStaticTags() {
        val statuses = listOf(
            StatusItem(content = "Sunset at the beach #sunset #beach")
        )

        val topTags = PixelfedRepository.extractTopTagsFromStatuses(statuses, topCount = Int.MAX_VALUE, staticTags = sampleStaticTags)

        assert(topTags.contains("sunset"))
        assert(topTags.contains("beach"))
        assert(topTags.containsAll(sampleStaticTags))
    }

    @Test
    fun testExtractTopTagsFromStatuses_concatenatesExtractedAndStaticTags() {
        val statuses = listOf(
            // Status 1: extracted from content -> #landscape, #photography. static in status item -> #photography, #sunset
            StatusItem(
                content = "Beautiful view #landscape #photography",
                tags = listOf(TagItem("photography"), TagItem("sunset"))
            ),
            // Status 2: extracted from content -> #landscape. static passed as staticTags arg -> #travel
            StatusItem(
                content = "Mountain trip #landscape"
            )
        )

        val staticTagsParam = listOf("#travel", "landscape")

        // Status 1 concatenated tags before distinct: [landscape, photography] + [travel, landscape, photography, sunset] -> distinct: [landscape, photography, travel, sunset]
        // Status 2 concatenated tags before distinct: [landscape] + [travel, landscape] -> distinct: [landscape, travel]
        // Counts across statuses:
        // landscape: 2
        // travel: 2
        // photography: 1
        // sunset: 1

        val topTags = PixelfedRepository.extractTopTagsFromStatuses(statuses, topCount = 20, staticTags = staticTagsParam)

        assertEquals(4, topTags.size)
        assertEquals("landscape", topTags[0])
        assertEquals("travel", topTags[1])
        assertEquals("photography", topTags[2])
        assertEquals("sunset", topTags[3])
    }

    @Test
    fun testParseTokenResponseBody_handlesValidAndInvalidJson() {
        // Valid token response
        val validTokenJson = """{"access_token":"token_12345","token_type":"Bearer","scope":"read write"}"""
        val token = PixelfedRepository.parseTokenResponseBody(validTokenJson)
        assertEquals("token_12345", token)

        // Missing access_token
        val missingTokenJson = """{"error":"invalid_grant"}"""
        val nullToken = PixelfedRepository.parseTokenResponseBody(missingTokenJson)
        assertEquals(null, nullToken)
    }

    @Test
    fun testParseRegistrationResponseBody_handlesValidAndInvalidJson() {
        // Valid JSON with client_id and client_secret
        val validJson = """{"id":123,"client_id":"id_abc","client_secret":"sec_123"}"""
        val (clientId, clientSecret) = PixelfedRepository.parseRegistrationResponseBody(validJson)
        assertEquals("id_abc", clientId)
        assertEquals("sec_123", clientSecret)

        // Numeric / boolean / primitive client_id and client_secret
        val numericJson = """{"client_id":12345,"client_secret":true}"""
        val (numId, numSecret) = PixelfedRepository.parseRegistrationResponseBody(numericJson)
        assertEquals("12345", numId)
        assertEquals("true", numSecret)

        // Missing fields
        val missingJson = """{"id":123}"""
        val (nullId, nullSecret) = PixelfedRepository.parseRegistrationResponseBody(missingJson)
        assertEquals(null, nullId)
        assertEquals(null, nullSecret)

        // Non-JSON / HTML
        val html = "<html><body>500 Error</body></html>"
        val (htmlId, htmlSecret) = PixelfedRepository.parseRegistrationResponseBody(html)
        assertEquals(null, htmlId)
        assertEquals(null, htmlSecret)
    }

    @Test
    fun testParseErrorResponseBody_handlesVariousJsonStructuresAndPrimitives() {
        // Standard error + error_description
        val json1 = """{"error":"invalid_client","error_description":"Client registration failed"}"""
        assertEquals("invalid_client: Client registration failed", PixelfedRepository.parseErrorResponseBody(json1))

        // Message field
        val json2 = """{"message":"The given data was invalid."}"""
        assertEquals("The given data was invalid.", PixelfedRepository.parseErrorResponseBody(json2))

        // Array body (no exception thrown)
        val json3 = """["An error occurred", "Details"]"""
        assertEquals("""["An error occurred", "Details"]""", PixelfedRepository.parseErrorResponseBody(json3))

        // Non-JSON string / HTML
        val html = "<html><body>500 Internal Server Error</body></html>"
        assertEquals(html, PixelfedRepository.parseErrorResponseBody(html))
    }

    @Test
    fun testPhotoListShiftingAndFocusIndex() {
        // Simulating the list operations in UploadScreen
        val list = mutableListOf("photo1", "photo2", "photo3", "photo4")

        // Shift index 2 ("photo3") left -> new list should be ["photo1", "photo3", "photo2", "photo4"]
        // Focus should follow "photo3" to index 1
        var focusedIndex = 2
        val itemToKeepInFocus = list[focusedIndex]

        val temp = list[focusedIndex]
        list[focusedIndex] = list[focusedIndex - 1]
        list[focusedIndex - 1] = temp
        focusedIndex -= 1

        assertEquals(listOf("photo1", "photo3", "photo2", "photo4"), list)
        assertEquals("photo3", list[focusedIndex])
        assertEquals("photo3", itemToKeepInFocus)

        // Shift index 1 ("photo3") right -> new list should be ["photo1", "photo2", "photo3", "photo4"]
        // Focus should follow "photo3" to index 2
        val tempRight = list[focusedIndex]
        list[focusedIndex] = list[focusedIndex + 1]
        list[focusedIndex + 1] = tempRight
        focusedIndex += 1

        assertEquals(listOf("photo1", "photo2", "photo3", "photo4"), list)
        assertEquals("photo3", list[focusedIndex])
    }

    @Test
    fun testPhotoListRemovalAndFocusAdjustment() {
        val list = mutableListOf("photo1", "photo2", "photo3")
        var focusedIndex = 2 // focus on photo3

        // Remove photo3 (last element)
        list.removeAt(focusedIndex)
        focusedIndex = when {
            list.isEmpty() -> 0
            focusedIndex >= list.size -> list.size - 1
            else -> focusedIndex
        }

        assertEquals(listOf("photo1", "photo2"), list)
        assertEquals(1, focusedIndex)
        assertEquals("photo2", list[focusedIndex])
    }

    @Test
    fun testPhotoSelectionLimitUpTo6() {
        val existing = listOf("photo1", "photo2", "photo3", "photo4")
        val newlySelected = listOf("photo5", "photo6", "photo7", "photo8")

        val combined = (existing + newlySelected).take(6)

        assertEquals(6, combined.size)
        assertEquals(listOf("photo1", "photo2", "photo3", "photo4", "photo5", "photo6"), combined)
    }
}
