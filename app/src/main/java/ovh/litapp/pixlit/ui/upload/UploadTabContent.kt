package ovh.litapp.pixlit.ui.upload

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ovh.litapp.pixlit.R
import ovh.litapp.pixlit.ui.upload.components.CollectionSelectionSection
import ovh.litapp.pixlit.ui.upload.components.ImagePagerSection
import ovh.litapp.pixlit.ui.upload.components.MetadataDisplay
import ovh.litapp.pixlit.ui.upload.components.OrderPreviewList
import ovh.litapp.pixlit.ui.upload.components.PositionSelectionSection
import ovh.litapp.pixlit.ui.upload.components.TagSelectionCloud

/**
 * The Upload tab body (tab 0), extracted from the former `UploadContent` (REF-02). Stateless
 * except for the pager/scroll handles the host owns; all inputs come from [state] and [callbacks].
 */
@Composable
fun UploadTabContent(
    state: UploadUiState,
    callbacks: UploadCallbacks,
    pagerState: PagerState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val selectedImageUris = state.selectedImageUris
    val currentPage = state.currentPage
    val isUploading = state.isUploading
    val resizeTo8Mb = state.resizeTo8Mb

    val maxPhotos = 6
    val uploadScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val removedMessage = stringResource(R.string.upload_removed_snackbar)
    val undoLabel = stringResource(R.string.action_undo)

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxPhotos)
    ) { uris ->
        if (uris.isNotEmpty()) callbacks.onAddImages(uris)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(uploadScrollState)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            ImagePagerSection(
                selectedImageUris = selectedImageUris,
                pagerState = pagerState
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedImageUris.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { callbacks.onShiftLeft(currentPage) },
                        enabled = !isUploading && currentPage > 0
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.upload_shift_left))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.upload_shift_left))
                    }

                    OutlinedButton(
                        onClick = {
                            val index = currentPage
                            val removedUri = selectedImageUris.getOrNull(index)
                            callbacks.onRemoveImage(index)
                            if (removedUri != null) {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = removedMessage,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        callbacks.onInsertImage(index, removedUri)
                                    }
                                }
                            }
                        },
                        enabled = !isUploading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, stringResource(R.string.upload_remove_photo_cd))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.upload_remove))
                    }

                    OutlinedButton(
                        onClick = { callbacks.onShiftRight(currentPage) },
                        enabled = !isUploading && currentPage < selectedImageUris.size - 1
                    ) {
                        Text(stringResource(R.string.upload_shift_right))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.upload_shift_right))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OrderPreviewList(
                    selectedImageUris = selectedImageUris,
                    currentPage = currentPage,
                    maxPhotos = maxPhotos,
                    onImageClick = { index -> callbacks.onPageChanged(index) }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            MetadataDisplay(
                currentPage = currentPage,
                totalImages = selectedImageUris.size,
                originalMetadata = state.originalMetadata,
                resizedMetadata = state.resizedMetadata,
                isCalculatingResized = state.isCalculatingResized,
                resizeTo8Mb = resizeTo8Mb
            )

            if (selectedImageUris.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .toggleable(
                        value = resizeTo8Mb,
                        role = Role.Checkbox,
                        enabled = !isUploading,
                        onValueChange = callbacks.onResizeToggled
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = resizeTo8Mb,
                    onCheckedChange = null,
                    enabled = !isUploading
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.upload_resize_label),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !isUploading && selectedImageUris.size < maxPhotos,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        selectedImageUris.isEmpty() -> stringResource(R.string.upload_select_photos, maxPhotos)
                        selectedImageUris.size < maxPhotos -> stringResource(R.string.upload_add_more_photos, selectedImageUris.size, maxPhotos)
                        else -> stringResource(R.string.upload_max_photos, maxPhotos, maxPhotos)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            CaptionField(
                value = state.caption,
                onValueChange = callbacks.onCaptionChanged,
                onInsertHash = callbacks.onInsertHash,
                remoteTagSuggestions = state.remoteTagSuggestions,
                topTags = state.topTags,
                onTagSearchQueryChanged = callbacks.onTagSearchQueryChanged,
                onTagSuggestionClick = callbacks.onTagSuggestionClick,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            val charCount = state.caption.text.length
            val pfLimit = 500
            val bsLimit = 300
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.upload_counter_pf, charCount, pfLimit),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (charCount > pfLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.upload_counter_bs, charCount, bsLimit),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (charCount > bsLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            PositionSelectionSection(
                selectedPlace = state.selectedPlace,
                customLocationName = state.customLocationName,
                isExifAutoDetected = state.isExifAutoDetected,
                isExifMissing = state.isExifMissing,
                placeSearchQuery = state.placeSearchQuery,
                placeSearchResults = state.placeSearchResults,
                isSearchingPlaces = state.isSearchingPlaces,
                onQueryChanged = callbacks.onPlaceQueryChanged,
                onSelectPlace = callbacks.onSelectPlace,
                onSetCustomLocation = callbacks.onSetCustomLocation,
                onClearLocation = callbacks.onClearLocation
            )

            Spacer(modifier = Modifier.height(12.dp))

            TagSelectionCloud(
                topTags = state.topTags,
                isLoadingTags = state.isLoadingTags,
                onTagClick = { callbacks.onTagClick(it) },
                onRefreshClick = { callbacks.onRefreshTags() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            CollectionSelectionSection(
                collections = state.userCollections,
                selectedCollectionIds = state.selectedCollectionIds,
                isLoadingCollections = state.isLoadingCollections,
                onCollectionToggle = callbacks.onCollectionToggle,
                onCreateCollection = callbacks.onCreateCollection
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding(),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = { callbacks.onUpload() },
                    enabled = !isUploading && selectedImageUris.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            if (selectedImageUris.size > 1) {
                                pluralStringResource(
                                    R.plurals.upload_photos,
                                    selectedImageUris.size,
                                    selectedImageUris.size
                                )
                            } else {
                                stringResource(R.string.upload_photo)
                            }
                        )
                    }
                }
            }
        }
    }
}
