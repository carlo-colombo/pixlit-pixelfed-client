package ovh.litapp.pixlit.ui.upload.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.ui.tooling.preview.Preview
import ovh.litapp.pixlit.ui.theme.PixlitTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagSelectionCloud(
    topTags: List<String>,
    isLoadingTags: Boolean,
    onTagClick: (String) -> Unit,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Top Tags",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = onRefreshClick,
                enabled = !isLoadingTags
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh tags"
                )
            }
        }

        if (isLoadingTags) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        } else if (topTags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                topTags.forEach { tag ->
                    SuggestionChip(
                        onClick = { onTagClick(tag) },
                        label = { Text("#$tag") }
                    )
                }
            }
        } else {
            Text(
                text = "No tags found",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TagSelectionCloudPreview() {
    PixlitTheme {
        TagSelectionCloud(
            topTags = listOf("photography", "art", "nature", "travel", "food"),
            isLoadingTags = false,
            onTagClick = {},
            onRefreshClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun TagSelectionCloudLoadingPreview() {
    PixlitTheme {
        TagSelectionCloud(
            topTags = emptyList(),
            isLoadingTags = true,
            onTagClick = {},
            onRefreshClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
