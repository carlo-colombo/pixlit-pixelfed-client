package ovh.litapp.pixlit.data.repository

import ovh.litapp.pixlit.data.api.BlueskyApi
import javax.inject.Inject

private const val ART_SHOW_ACTOR = "churchstreetimages.com"
private const val ROBYN_ACTOR = "handcranked.bsky.social"
private val THEME_PATTERN = Regex("(?i)The theme is\\s+(#\\w+)")
private val DATE_RANGE_PATTERN = Regex("(?i)\\b((?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+\\d{1,2})\\s*[-–]\\s*((?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)?\\s*\\d{1,2})")
private val DAY_PATTERN = Regex("(?i)^\\s*(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)(?:\\s*,?\\s*[^:#\\n]*)?(?:\\s*[:\\-]\\s*)?(.*)$")
private val TAG_PATTERN = Regex("(#[\\p{L}\\p{N}_]+)(?:\\s*\\(([^)]*)\\))?")

data class WeeklyChallenge(
    val dateRange: String,
    val tagsByDay: Map<String, List<ChallengeTag>>
)

data class ChallengeTag(
    val name: String,
    val description: String? = null,
    val includedTags: List<String> = listOf(name)
)

class BlueskyArtShowRepository @Inject constructor(private val api: BlueskyApi) {
    suspend fun fetchTheme(): String? {
        val profile = api.getProfile(ART_SHOW_ACTOR).body() ?: return null
        val uri = profile.pinnedPost?.uri ?: return null
        val post = api.getPosts(uri).body()?.posts?.firstOrNull() ?: return null
        return post.record?.text?.let { THEME_PATTERN.find(it)?.groupValues?.get(1) }
    }

    suspend fun fetchWeeklyChallenge(): Result<WeeklyChallenge> = runCatching {
        val profile = api.getProfile(ROBYN_ACTOR).body()
            ?: error("Robyn's profile could not be loaded")
        val uri = profile.pinnedPost?.uri ?: error("Robyn has no pinned post")
        val post = api.getPosts(uri).body()?.posts?.firstOrNull()
            ?: error("Robyn's pinned post could not be loaded")
        val descriptions = post.record?.embed?.images?.mapNotNull { it.alt }.orEmpty()
        val dailyDescription = descriptions.firstOrNull { description ->
            description.lines().any { DAY_PATTERN.matches(it) }
        }
        val source = listOfNotNull(post.record?.text, dailyDescription).joinToString("\n")
        parseWeeklyChallenge(source)
    }
}

fun parseBlueSkyArtShowTheme(text: String?): String? =
    text?.let { THEME_PATTERN.find(it)?.groupValues?.get(1) }

fun parseWeeklyChallenge(text: String): WeeklyChallenge {
    val dateMatch = DATE_RANGE_PATTERN.find(text)
    val dateRange = dateMatch?.let { "${it.groupValues[1]}-${it.groupValues[2].trim()}" } ?: "This week's challenge"
    val days = linkedMapOf<String, MutableList<ChallengeTag>>()
    var currentDay: String? = null
    text.lines().forEach { line ->
        val match = DAY_PATTERN.matchEntire(line)
        if (match != null) {
            val day = match.groupValues[1].replaceFirstChar { it.uppercase() }
            currentDay = day
            days.getOrPut(day) { mutableListOf() }.addAll(parseChallengeTags(match.groupValues[2]))
        } else if (currentDay != null) {
            days[currentDay]!!.addAll(parseChallengeTags(line))
        }
    }
    return WeeklyChallenge(
        dateRange = dateRange,
        tagsByDay = days.mapValues { (_, tags) -> tags.distinctBy { it.name.lowercase() } }
    )
}

private fun parseChallengeTags(line: String): List<ChallengeTag> {
    val matches = TAG_PATTERN.findAll(line).toList()
    if (matches.size > 1 && line.contains("+")) {
        val tags = matches.map { it.groupValues[1] }
        return listOf(ChallengeTag(tags.joinToString(" + "), includedTags = tags))
    }
    return matches.map { match ->
        ChallengeTag(match.groupValues[1], match.groupValues[2].takeIf { it.isNotBlank() })
    }
}
