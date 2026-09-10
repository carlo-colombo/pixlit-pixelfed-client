package ovh.litapp.pixlit.ui.upload.components

import android.text.Html
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ovh.litapp.pixlit.data.api.StatusItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentPostsSection(
    statuses: List<StatusItem>,
    onCopyPost: (String) -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your latest posts", style = MaterialTheme.typography.titleLarge)
                Text("${statuses.size} posts loaded", style = MaterialTheme.typography.bodyMedium)
            }
        }
        itemsIndexed(statuses, key = { index, status -> status.getIdString() ?: "post-$index" }) { _, status ->
            RecentPostCard(status, onCopyPost)
        }
        item {
            Button(
                onClick = onLoadMore,
                enabled = !isLoadingMore && statuses.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoadingMore) CircularProgressIndicator(Modifier.size(20.dp))
                else Text("Load 10 more posts")
            }
        }
    }
}

@Composable
private fun RecentPostCard(status: StatusItem, onCopyPost: (String) -> Unit) {
    val attachments = status.mediaAttachments.orEmpty()
    val rawText = status.text?.takeIf { it.isNotBlank() }
        ?: status.content?.takeIf { it.isNotBlank() }
        ?: status.description.orEmpty()
    val text = Html.fromHtml(rawText, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    val tags = status.tags.orEmpty().mapNotNull { it.name?.takeIf(String::isNotBlank) }
        .map { "#${it.removePrefix("#")}" }
    val missingTags = tags.filterNot { tag ->
        Regex("(?i)(?<!\\w)${Regex.escape(tag)}(?!\\w)").containsMatchIn(text)
    }
    val copyText = listOf(text, missingTags.joinToString(" ").takeIf { it.isNotBlank() })
        .filterNotNull().joinToString("\n\n")

    Card(modifier = Modifier.fillMaxWidth().height(420.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (attachments.isNotEmpty()) {
                val imagePagerState = rememberPagerState(pageCount = { attachments.size })
                HorizontalPager(
                    state = imagePagerState,
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                ) { page ->
                    AsyncImage(
                        model = attachments[page].url ?: attachments[page].previewUrl,
                        contentDescription = attachments[page].description,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(attachments.size) { index ->
                        Surface(
                            modifier = Modifier.padding(horizontal = 3.dp).size(if (index == imagePagerState.currentPage) 8.dp else 6.dp),
                            shape = MaterialTheme.shapes.small,
                            color = if (index == imagePagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            }
                        ) {}
                    }
                }
            }
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FavoriteBorder, contentDescription = "Likes")
                    Text(" ${status.favouritesCount}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Repeat, contentDescription = "Reposts")
                    Text(" ${status.reblogsCount}")
                }
            }
            Button(onClick = { onCopyPost(copyText) }, enabled = copyText.isNotBlank()) {
                Text("Copy text and tags to new post")
            }
        }
    }
}
