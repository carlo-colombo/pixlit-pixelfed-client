package ovh.litapp.pixlit.data.repository

import java.time.*
import java.time.format.TextStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueskyArtShowRepositoryTest {
    @Test fun `parses daily challenge tags from image description`() {
        val challenge = parseWeeklyChallenge("Daily Art Prompts: Aug 31 - Sept 6\nMonday, August 31\n#PalacesAndGardens #Painting\nTuesday, September 1\n#Drawing")

        assertEquals("Aug 31-Sept 6", challenge.dateRange)
        assertEquals(listOf(ChallengeTag("#PalacesAndGardens"), ChallengeTag("#Painting")), challenge.tagsByDay["Monday"])
        assertEquals(listOf(ChallengeTag("#Drawing")), challenge.tagsByDay["Tuesday"])
    }

    @Test fun `parses theme`() {
        assertEquals("#Minimal", parseBlueSkyArtShowTheme("The theme is #Minimal"))
        assertEquals("#Summer", parseBlueSkyArtShowTheme("The theme is #Summer"))
    }

    @Test fun `groups tags joined by plus`() {
        val challenge = parseWeeklyChallenge("Monday\n#ColorADay + #PinkMon")

        assertEquals(listOf("#ColorADay", "#PinkMon"), challenge.tagsByDay["Monday"]!!.single().includedTags)
    }

    @Test fun `returns null for invalid text`() {
        assertNull(parseBlueSkyArtShowTheme("No theme here"))
        assertNull(parseBlueSkyArtShowTheme(null))
    }

    @Test fun `parses all days of week matching standard day names`() {
        val text = """
            Monday: #Mon
            Tuesday: #Tue
            Wednesday: #Wed
            Thursday: #Thu
            Friday: #Fri
            Saturday: #Sat
            Sunday: #Sun
        """.trimIndent()
        val challenge = parseWeeklyChallenge(text)

        DayOfWeek.values().forEach { dayOfWeek ->
            val dayName = dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val tags = challenge.tagsByDay[dayName]
            assertNotNull("Tags for $dayName should not be null", tags)
            assertTrue("Tags for $dayName should not be empty", tags!!.isNotEmpty())
        }
    }

    @Test fun `converts AT URI to Bluesky post web URL`() {
        assertEquals(
            "https://bsky.app/profile/did:plc:12345/post/3kabc123",
            atUriToPostUrl("at://did:plc:12345/app.bsky.feed.post/3kabc123")
        )
        assertEquals(
            "https://bsky.app/profile/handcranked.bsky.social/post/3kabc123",
            atUriToPostUrl("at://handcranked.bsky.social/app.bsky.feed.post/3kabc123")
        )
        assertEquals(
            "https://bsky.app/profile/handcranked.bsky.social/post/3kabc123",
            atUriToPostUrl("https://bsky.app/profile/handcranked.bsky.social/post/3kabc123")
        )
    }

    @Test fun `WeeklyChallenge includes postUrl when provided`() {
        val challenge = parseWeeklyChallenge("Monday: #Mon").copy(
            postUrl = "https://bsky.app/profile/handcranked.bsky.social/post/3kabc123"
        )
        assertEquals("https://bsky.app/profile/handcranked.bsky.social/post/3kabc123", challenge.postUrl)
    }
}
