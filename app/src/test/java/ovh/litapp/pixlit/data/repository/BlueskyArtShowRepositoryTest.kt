package ovh.litapp.pixlit.data.repository

import java.time.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
