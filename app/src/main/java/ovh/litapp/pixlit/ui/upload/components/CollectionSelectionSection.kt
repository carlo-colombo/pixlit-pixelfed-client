package ovh.litapp.pixlit.ui.upload.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.google.gson.JsonPrimitive
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.ui.theme.PixlitTheme

/**
 * Collection picker with an inline create dialog.
 *
 * [onCreateCollection] is fire-and-forget, so the dialog must not dismiss on the
 * Create tap itself: dismissal is driven by the collection list gaining the new
 * item (UploadViewModel.createAndSelectCollection prepends and selects it only on
 * success). A failed create therefore leaves the dialog open with the typed input
 * intact so the user can retry.
 *
 * Note for the ViewModel owner: nothing surfaces the failure to the user yet.
 * Once the typed create result exists (DATA-05), report it back and show an error
 * message (UX-03 snackbar) instead of relying on the list change alone.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CollectionSelectionSection(
    collections: List<CollectionItem>,
    selectedCollectionIds: Set<String>,
    isLoadingCollections: Boolean,
    onCollectionToggle: (String) -> Unit,
    onCreateCollection: (String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    // Set by the Create tap; cleared only when the new collection shows up in
    // [collections]. Keeps the dialog keyed to the pending create rather than the tap.
    var isCreatePending by remember { mutableStateOf(false) }
    var collectionIdsBeforeCreate by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(collections, isCreatePending) {
        if (!isCreatePending) return@LaunchedEffect
        val currentIds = collections.mapNotNull { it.getIdString() }.toSet()
        if (currentIds.any { it !in collectionIdsBeforeCreate }) {
            isCreatePending = false
            showCreateDialog = false
            newTitle = ""
            newDescription = ""
        }
    }

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
            if (isLoadingCollections) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Button to create a new collection
            AssistChip(
                onClick = { showCreateDialog = true },
                label = { Text("New Collection") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create collection",
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

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
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                newTitle = ""
                newDescription = ""
                isCreatePending = false
            },
            title = { Text("Create New Collection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Collection Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newDescription,
                        onValueChange = { newDescription = it },
                        label = { Text("Description (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTitle.isNotBlank()) {
                            collectionIdsBeforeCreate = collections.mapNotNull { it.getIdString() }.toSet()
                            isCreatePending = true
                            onCreateCollection(newTitle, newDescription.ifBlank { null })
                        }
                    },
                    enabled = newTitle.isNotBlank()
                ) {
                    Text("Create & Select")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateDialog = false
                        newTitle = ""
                        newDescription = ""
                        isCreatePending = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
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
            onCreateCollection = { _, _ -> },
            modifier = Modifier.padding(16.dp)
        )
    }
}
