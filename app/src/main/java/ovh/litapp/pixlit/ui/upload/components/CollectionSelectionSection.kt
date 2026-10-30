package ovh.litapp.pixlit.ui.upload.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.google.gson.JsonPrimitive
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.data.api.MAX_COLLECTIONS_PER_POST
import ovh.litapp.pixlit.ui.theme.PixlitTheme

/**
 * Collection picker for the upload tab.
 *
 * Pixelfed exposes no API to create a collection: the web UI does it through a session-only
 * `GET /i/collections/create` that `firstOrCreate`s a draft, and the OAuth API only has list,
 * update, add-item and delete routes. The picker therefore lists existing collections and points
 * the user at the web UI instead of offering a create action that would always 404.
 *
 * A post can join at most [MAX_COLLECTIONS_PER_POST] collections: the status creator validates
 * `collection_ids` as `max:3` and would reject the post itself above that. The ViewModel refuses a
 * fourth selection; the hint here explains the limit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionSelectionSection(
    collections: List<CollectionItem>,
    selectedCollectionIds: Set<String>,
    isLoadingCollections: Boolean,
    onCollectionToggle: (String) -> Unit,
    onRefreshCollections: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val count = selectedCollectionIds.size
            Text(
                text = "Add to Collections ($count selected)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLoadingCollections) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(
                    onClick = onRefreshCollections,
                    enabled = !isLoadingCollections
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh collections"
                    )
                }
            }
        }

        if (collections.isEmpty() && !isLoadingCollections) {
            Text(
                text = "No collections yet. Create one in Pixelfed on the web, then refresh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            collections.forEach { collection ->
                val id = collection.getIdString() ?: return@forEach
                val isSelected = selectedCollectionIds.contains(id)

                FilterChip(
                    selected = isSelected,
                    onClick = { onCollectionToggle(id) },
                    label = { Text(collection.getDisplayName()) },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null
                )
            }
        }

        if (collections.isNotEmpty()) {
            Text(
                text = "Up to $MAX_COLLECTIONS_PER_POST collections per post.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private class SampleCollectionsProvider : PreviewParameterProvider<List<CollectionItem>> {
    override val values = sequenceOf(
        listOf(
            CollectionItem(id = JsonPrimitive("1"), title = "Street photography"),
            CollectionItem(id = JsonPrimitive("2"), title = "Travel")
        ),
        emptyList()
    )
}

@Preview(showBackground = true)
@Composable
fun CollectionSelectionSectionPreview(
    @PreviewParameter(SampleCollectionsProvider::class) collections: List<CollectionItem>
) {
    PixlitTheme {
        CollectionSelectionSection(
            collections = collections,
            selectedCollectionIds = setOf("1"),
            isLoadingCollections = false,
            onCollectionToggle = {},
            onRefreshCollections = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
