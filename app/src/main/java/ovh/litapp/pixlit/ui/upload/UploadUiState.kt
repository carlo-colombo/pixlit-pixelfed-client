package ovh.litapp.pixlit.ui.upload

import android.net.Uri
import androidx.compose.ui.text.input.TextFieldValue
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.data.api.PlaceItem
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.repository.CacheSummaryItem
import ovh.litapp.pixlit.data.repository.TagCount
import ovh.litapp.pixlit.data.work.UploadJobState
import ovh.litapp.pixlit.utils.ImageMetadata

/**
 * The single source of truth for the upload screen (REF-01). One immutable object replaces the
 * ViewModel's former per-field `StateFlow`s, so [UploadViewModel.uiState] is the only flow the
 * screen collects and a field change is a single [UploadUiState.copy] instead of a scattered write.
 *
 * The defaults are the empty screen: every field's initial value here matches the value the
 * corresponding `MutableStateFlow` used to be constructed with.
 */
data class UploadUiState(
    val selectedImageUris: List<Uri> = emptyList(),
    val caption: TextFieldValue = TextFieldValue(""),
    /** Per-photo alt text (UP-10), keyed by the picked URI. Never the caption. */
    val altTexts: Map<Uri, String> = emptyMap(),
    val remoteTagSuggestions: List<String> = emptyList(),
    val cacheSummary: List<CacheSummaryItem> = emptyList(),
    val resizeTo8Mb: Boolean = false,
    val isUploading: Boolean = false,
    /** Per-photo progress of the work-managed upload (UP-05); `null` before the first enqueue. */
    val uploadProgress: UploadJobState? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val topTags: List<TagCount> = emptyList(),
    val recentStatuses: List<StatusItem> = emptyList(),
    val visibleRecentPostCount: Int = 10,
    val isLoadingMorePosts: Boolean = false,
    val isLoadingTags: Boolean = false,
    val isRefreshing: Boolean = false,
    /**
     * Starts empty: a genuinely empty collection list must reach the UI as an empty state instead
     * of the fabricated placeholders the facade used to substitute (DATA-05 / UP-13).
     */
    val userCollections: List<CollectionItem> = emptyList(),
    val selectedCollectionIds: Set<String> = emptySet(),
    val isLoadingCollections: Boolean = false,
    val currentPage: Int = 0,
    val originalMetadata: ImageMetadata? = null,
    val resizedMetadata: ImageMetadata? = null,
    val isCalculatingResized: Boolean = false,
    val selectedPlace: PlaceItem? = null,
    val customLocationName: String? = null,
    val isExifAutoDetected: Boolean = false,
    val isExifMissing: Boolean = false,
    val placeSearchQuery: String = "",
    val placeSearchResults: List<PlaceItem> = emptyList(),
    /** The place-search box spinner; EXIF geocoding has its own flag ([isDetectingExifLocation]). */
    val isSearchingPlaces: Boolean = false,
    val isDetectingExifLocation: Boolean = false
)

/**
 * The screen's lambdas, hoisted once by [UploadScreen] and passed down as a single argument
 * (REF-01). Every callback has an empty default so previews and extracted tabs can supply only the
 * ones they exercise.
 */
data class UploadCallbacks(
    val onLogout: () -> Unit = {},
    val onPlaceQueryChanged: (String) -> Unit = {},
    val onSelectPlace: (PlaceItem) -> Unit = {},
    val onSetCustomLocation: (String) -> Unit = {},
    val onClearLocation: () -> Unit = {},
    val onCollectionToggle: (String) -> Unit = {},
    val onCreateCollection: (String, String?) -> Unit = { _, _ -> },
    val onPageChanged: (Int) -> Unit = {},
    val onAddImages: (List<Uri>) -> Unit = {},
    val onShiftLeft: (Int) -> Unit = {},
    val onShiftRight: (Int) -> Unit = {},
    val onRemoveImage: (Int) -> Unit = {},
    val onInsertImage: (Int, Uri) -> Unit = { _, _ -> },
    val onResizeToggled: (Boolean) -> Unit = {},
    val onCaptionChanged: (TextFieldValue) -> Unit = {},
    val onInsertHash: () -> Unit = {},
    val onTagSearchQueryChanged: (String) -> Unit = {},
    val onRefreshCacheSummary: () -> Unit = {},
    val onTagSuggestionClick: (String) -> Unit = {},
    val onTagClick: (String) -> Unit = {},
    val onSocialTagClick: (List<String>) -> Unit = {},
    val onCopyPost: (String) -> Unit = {},
    val onLoadMorePosts: () -> Unit = {},
    val onRefreshRecentPosts: () -> Unit = {},
    val onRefreshTags: () -> Unit = {},
    val onUpload: () -> Unit = {}
)
