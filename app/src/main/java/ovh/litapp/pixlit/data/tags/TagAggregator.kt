package ovh.litapp.pixlit.data.tags

import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.repository.TagCount

/**
 * Pure tag aggregation over statuses (DATA-06 step 1 / F-12). No Android or Context dependency, so
 * the ranking rules are unit-testable on the JVM. [TagCount] stays in the repository package because
 * it is part of the repository's public surface consumed by the UI.
 */
object TagAggregator {

    /** How many tags the default ranking surfaces; callers can pass a larger [topCount]. */
    const val MAX_DISPLAYED_TAGS = 30

    fun extractTagsFromPostsText(text: String): List<String> {
        return Regex("""#([\p{L}\p{N}_-]+)""")
            .findAll(text)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()
    }

    fun extractTopTagsFromStatuses(
        statuses: List<StatusItem>,
        topCount: Int = 20,
        staticTags: List<String> = emptyList()
    ): List<String> {
        return extractTopTagCountsFromStatuses(statuses, topCount, staticTags)
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.name })
            .map { it.name }
    }

    fun extractTopTagCountsFromStatuses(
        statuses: List<StatusItem>,
        topCount: Int = MAX_DISPLAYED_TAGS,
        staticTags: List<String> = emptyList()
    ): List<TagCount> {
        val tagCounts = mutableMapOf<String, Int>()

        val sanitizedStaticParam = staticTags.mapNotNull { tag ->
            val s = tag.trim().removePrefix("#").lowercase()
            if (s.isNotEmpty()) s else null
        }.distinct()

        sanitizedStaticParam.forEach { tag ->
            tagCounts[tag] = 0
        }

        for (status in statuses) {
            // 1. Static tags
            val staticInStatus = mutableListOf<String>()
            sanitizedStaticParam.forEach { tag ->
                staticInStatus.add(tag)
            }
            status.tags?.forEach { tag ->
                val tagName = tag.name?.trim()?.removePrefix("#")
                if (!tagName.isNullOrEmpty()) {
                    staticInStatus.add(tagName.lowercase())
                }
            }

            // 2. Extracted tags from post text and media attachment descriptions
            val extractedInStatus = mutableListOf<String>()
            val mediaDescriptions = status.mediaAttachments?.mapNotNull { it.description } ?: emptyList()
            val textSources = listOfNotNull(status.content, status.text, status.description, status.spoilerText) + mediaDescriptions
            val combinedText = textSources.joinToString(" ")
            if (combinedText.isNotEmpty()) {
                val hashtagRegex = Regex("""#([\p{L}\p{N}_-]+)""")
                hashtagRegex.findAll(combinedText).forEach { matchResult ->
                    val tag = matchResult.groupValues[1].lowercase()
                    if (tag.isNotEmpty()) {
                        extractedInStatus.add(tag)
                    }
                }
            }

            val uniqueInStatus = (extractedInStatus + staticInStatus).distinct()

            for (tag in uniqueInStatus) {
                tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
            }
        }

        // Select the most-used tags before alphabetizing the displayed result.
        return tagCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(topCount)
            .sortedBy { it.key }
            .map { TagCount(it.key, it.value) }
    }
}
