package ovh.litapp.pixlit.data.repository

import ovh.litapp.pixlit.data.api.BlueskyApi
import javax.inject.Inject

private const val BLUESKY_ACTOR = "churchstreetimages.com"
private val THEME_PATTERN = Regex("(?i)The theme is\\s+(#\\w+)")

class BlueskyArtShowRepository @Inject constructor(private val api: BlueskyApi) {
    suspend fun fetchTheme(): String? {
        val profile = api.getProfile(BLUESKY_ACTOR).body() ?: return null
        val uri = profile.pinnedPost?.uri ?: return null
        val post = api.getPosts(uri).body()?.posts?.firstOrNull() ?: return null
        return post.record?.text?.let { THEME_PATTERN.find(it)?.groupValues?.get(1) }
    }
}

fun parseBlueSkyArtShowTheme(text: String?): String? =
    text?.let { THEME_PATTERN.find(it)?.groupValues?.get(1) }
