package ovh.litapp.pixlit.data.api

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import ovh.litapp.pixlit.di.NetworkModule

/**
 * The explicit null boundary of the response DTOs (DATA-10 / 04 F-18).
 *
 * The production [Gson] runs `Strictness.LENIENT` and instantiates DTOs through `Unsafe`, so a
 * missing key lands in the object as `null` regardless of the Kotlin default. These cases pin that
 * a missing collection is a value the caller can handle, and that an unparseable body fails loudly
 * instead of producing a half-filled DTO.
 */
class ApiDtoNullabilityTest {

    private val gson: Gson = NetworkModule.provideGson()

    @Test
    fun `a search response without a hashtags key reports no hashtags`() {
        val response = gson.fromJson("""{"accounts":[],"statuses":[]}""", HashtagSearchResponse::class.java)

        assertNull(response.hashtags)
        // What the repository does with it: a missing collection is "no results", never an NPE.
        assertEquals(emptyList<String>(), response.hashtags.orEmpty())
    }

    @Test
    fun `a bluesky feed without posts reports no posts`() {
        val posts = gson.fromJson("""{"feedContext":"x"}""", BlueskyPosts::class.java)

        assertNull(posts.posts)
        assertNull(posts.posts?.firstOrNull())
    }

    @Test
    fun `a malformed body is rejected, not silently accepted`() {
        // A proxy's HTML error page is the realistic shape that reaches the converter.
        assertThrows(JsonParseException::class.java) {
            gson.fromJson("<html>504 Gateway Timeout</html>", HashtagSearchResponse::class.java)
        }
    }

    @Test
    fun `toSafeString covers null, missing, primitive and object ids`() {
        val absent: JsonElement? = null
        assertNull(absent.toSafeString())
        assertNull(JsonNull.INSTANCE.toSafeString())
        assertEquals("42", JsonPrimitive(42).toSafeString())
        assertEquals("abc", JsonPrimitive("abc").toSafeString())
        assertEquals("""{"a":1}""", JsonParser.parseString("""{"a":1}""").toSafeString())
    }
}
