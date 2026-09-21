package ovh.litapp.pixlit.ui.upload

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.data.api.PlaceItem
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.data.api.toSafeString
import ovh.litapp.pixlit.data.repository.TagCount
import ovh.litapp.pixlit.data.repository.CacheSummaryItem
import ovh.litapp.pixlit.ui.theme.PixlitTheme
import ovh.litapp.pixlit.ui.upload.components.*
import android.net.Uri
import android.app.TimePickerDialog
import ovh.litapp.pixlit.data.reminder.ReminderPreferences
import ovh.litapp.pixlit.data.reminder.ReminderScheduler
import java.time.LocalTime
import ovh.litapp.pixlit.utils.ImageMetadata
import ovh.litapp.pixlit.ui.social.SocialScreen

private class CaptionSuggestionPositionProvider(
    private val cursorX: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val requestedX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left
        } else {
            anchorBounds.right - popupContentSize.width
        }
        val x = (requestedX + cursorX + 24).coerceIn(
            0,
            (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        )
        return IntOffset(x, 8)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UploadScreen(
    repository: PixelfedRepository,
    onLogout: () -> Unit,
    prefillTheme: String? = null,
    onPrefillConsumed: () -> Unit = {},
    sharedImageUris: List<Uri> = emptyList(),
    onSharedImageUrisConsumed: () -> Unit = {},
    reminderPreferences: ReminderPreferences? = null,
    reminderScheduler: ReminderScheduler? = null,
    onSimulateNotification: () -> Unit = {},
    viewModel: UploadViewModel = hiltViewModel()
) {
    val selectedImageUris by viewModel.selectedImageUris.collectAsState()
    val captionState by viewModel.captionState.collectAsState()
    val resizeTo8Mb by viewModel.resizeTo8Mb.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isError by viewModel.isError.collectAsState()
    val topTags by viewModel.topTags.collectAsState()
    val remoteTagSuggestions by viewModel.remoteTagSuggestions.collectAsState()
    val cacheSummary by viewModel.cacheSummary.collectAsState()
    val recentStatuses by viewModel.recentStatuses.collectAsState()
    val visibleRecentPostCount by viewModel.visibleRecentPostCount.collectAsState()
    val isLoadingMorePosts by viewModel.isLoadingMorePosts.collectAsState()
    val isLoadingTags by viewModel.isLoadingTags.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val originalMetadata by viewModel.originalMetadata.collectAsState()
    val resizedMetadata by viewModel.resizedMetadata.collectAsState()
    val isCalculatingResized by viewModel.isCalculatingResized.collectAsState()
    val userCollections by viewModel.userCollections.collectAsState()
    val selectedCollectionIds by viewModel.selectedCollectionIds.collectAsState()
    val isLoadingCollections by viewModel.isLoadingCollections.collectAsState()

    val selectedPlace by viewModel.selectedPlace.collectAsState()
    val customLocationName by viewModel.customLocationName.collectAsState()
    val isExifAutoDetected by viewModel.isExifAutoDetected.collectAsState()
    val isExifMissing by viewModel.isExifMissing.collectAsState()
    val placeSearchQuery by viewModel.placeSearchQuery.collectAsState()
    val placeSearchResults by viewModel.placeSearchResults.collectAsState()
    val isSearchingPlaces by viewModel.isSearchingPlaces.collectAsState()

    LaunchedEffect(prefillTheme) {
        if (prefillTheme != null) {
            viewModel.prefillArtShowTags(prefillTheme)
            onPrefillConsumed()
        }
    }

    LaunchedEffect(sharedImageUris) {
        if (sharedImageUris.isNotEmpty()) {
            viewModel.addImages(sharedImageUris)
            onSharedImageUrisConsumed()
        }
    }

    UploadContent(
        selectedImageUris = selectedImageUris,
        captionState = captionState,
        resizeTo8Mb = resizeTo8Mb,
        isUploading = isUploading,
        statusMessage = statusMessage,
        isError = isError,
        topTags = topTags,
        remoteTagSuggestions = remoteTagSuggestions,
        cacheSummary = cacheSummary,
        recentStatuses = recentStatuses,
        visibleRecentPostCount = visibleRecentPostCount,
        isLoadingMorePosts = isLoadingMorePosts,
        isLoadingTags = isLoadingTags,
        isRefreshing = isRefreshing,
        currentPage = currentPage,
        originalMetadata = originalMetadata,
        resizedMetadata = resizedMetadata,
        isCalculatingResized = isCalculatingResized,
        userCollections = userCollections,
        selectedCollectionIds = selectedCollectionIds,
        isLoadingCollections = isLoadingCollections,
        selectedPlace = selectedPlace,
        customLocationName = customLocationName,
        isExifAutoDetected = isExifAutoDetected,
        isExifMissing = isExifMissing,
        placeSearchQuery = placeSearchQuery,
        placeSearchResults = placeSearchResults,
        isSearchingPlaces = isSearchingPlaces,
        onLogout = onLogout,
        onPlaceQueryChanged = { viewModel.onPlaceSearchQueryChanged(it) },
        onSelectPlace = { viewModel.selectPlace(it) },
        onSetCustomLocation = { viewModel.setCustomLocation(it) },
        onClearLocation = { viewModel.clearLocation() },
        onCollectionToggle = { viewModel.toggleCollectionSelection(it) },
        onCreateCollection = { title, desc -> viewModel.createAndSelectCollection(title, desc) },
        onPageChanged = { viewModel.onPageChanged(it) },
        onAddImages = { viewModel.addImages(it) },
        onShiftLeft = { viewModel.shiftLeft(it) },
        onShiftRight = { viewModel.shiftRight(it) },
        onRemoveImage = { viewModel.removeImageAt(it) },
        onResizeToggled = { viewModel.onResizeToggled(it) },
        onCaptionChanged = { viewModel.onCaptionChanged(it) },
        onInsertHash = { viewModel.insertHash() },
        onTagSearchQueryChanged = { viewModel.searchTagSuggestions(it) },
        onRefreshCacheSummary = viewModel::refreshCacheSummary,
        onTagSuggestionClick = { viewModel.insertTagSuggestion(it) },
        onTagClick = { viewModel.insertTag(it) },
        onSocialTagClick = { tags -> tags.forEach(viewModel::insertTag) },
        onCopyPost = { text -> viewModel.onCaptionChanged(TextFieldValue(text)) },
        onLoadMorePosts = viewModel::loadMoreRecentPosts,
        onRefreshRecentPosts = viewModel::refreshRecentPosts,
        onRefreshTags = { viewModel.fetchTags(forceRefresh = true) },
        onUpload = { viewModel.upload() },
        reminderPreferences = reminderPreferences,
        reminderScheduler = reminderScheduler,
        onSimulateNotification = onSimulateNotification
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UploadContent(
    selectedImageUris: List<Uri>,
    captionState: TextFieldValue,
    resizeTo8Mb: Boolean,
    isUploading: Boolean,
    statusMessage: String?,
    isError: Boolean,
    topTags: List<TagCount>,
    remoteTagSuggestions: List<String> = emptyList(),
    cacheSummary: List<CacheSummaryItem> = emptyList(),
    recentStatuses: List<StatusItem>,
    visibleRecentPostCount: Int = 10,
    isLoadingMorePosts: Boolean = false,
    isLoadingTags: Boolean,
    isRefreshing: Boolean = false,
    currentPage: Int,
    originalMetadata: ImageMetadata?,
    resizedMetadata: ImageMetadata?,
    isCalculatingResized: Boolean,
    userCollections: List<CollectionItem> = emptyList(),
    selectedCollectionIds: Set<String> = emptySet(),
    isLoadingCollections: Boolean = false,
    selectedPlace: PlaceItem? = null,
    customLocationName: String? = null,
    isExifAutoDetected: Boolean = false,
    isExifMissing: Boolean = false,
    placeSearchQuery: String = "",
    placeSearchResults: List<PlaceItem> = emptyList(),
    isSearchingPlaces: Boolean = false,
    onLogout: () -> Unit = {},
    onPlaceQueryChanged: (String) -> Unit = {},
    onSelectPlace: (PlaceItem) -> Unit = {},
    onSetCustomLocation: (String) -> Unit = {},
    onClearLocation: () -> Unit = {},
    onCollectionToggle: (String) -> Unit = {},
    onCreateCollection: (String, String?) -> Unit = { _, _ -> },
    onPageChanged: (Int) -> Unit = {},
    onAddImages: (List<Uri>) -> Unit = {},
    onShiftLeft: (Int) -> Unit = {},
    onShiftRight: (Int) -> Unit = {},
    onRemoveImage: (Int) -> Unit = {},
    onResizeToggled: (Boolean) -> Unit = {},
    onCaptionChanged: (TextFieldValue) -> Unit = {},
    onInsertHash: () -> Unit = {},
    onTagSearchQueryChanged: (String) -> Unit = {},
    onRefreshCacheSummary: () -> Unit = {},
    onTagSuggestionClick: (String) -> Unit = {},
    onTagClick: (String) -> Unit = {},
    onSocialTagClick: (List<String>) -> Unit = {},
    onCopyPost: (String) -> Unit = {},
    onLoadMorePosts: () -> Unit = {},
    onRefreshRecentPosts: () -> Unit = {},
    onRefreshTags: () -> Unit = {},
    onUpload: () -> Unit = {},
    reminderPreferences: ReminderPreferences? = null,
    reminderScheduler: ReminderScheduler? = null,
    onSimulateNotification: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Upload", "Social", "Last", "Debug", "Settings")

    val maxPhotos = 6
    val uploadScrollState = rememberScrollState()
    val captionBringIntoViewRequester = remember { BringIntoViewRequester() }
    var captionFocused by remember { mutableStateOf(false) }
    var showTagPopup by remember { mutableStateOf(false) }
    val tagSearchFocusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    LaunchedEffect(captionFocused) {
        if (captionFocused) {
            kotlinx.coroutines.delay(100)
            captionBringIntoViewRequester.bringIntoView()
        }
    }
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { selectedImageUris.size }
    )

    // Sync PagerState with outer currentPage
    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    LaunchedEffect(currentPage) {
        if (currentPage != pagerState.currentPage && currentPage < selectedImageUris.size) {
            pagerState.scrollToPage(currentPage)
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 3) onRefreshCacheSummary()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        onAddImages(uris)
    }

    val hashtagContext = findHashtagContext(captionState)
    var tagSearchQuery by remember { mutableStateOf("") }
    LaunchedEffect(hashtagContext?.start, hashtagContext?.end, hashtagContext?.query) {
        tagSearchQuery = hashtagContext?.query.orEmpty()
    }
    LaunchedEffect(tagSearchQuery) {
        if (hashtagContext != null) onTagSearchQueryChanged(tagSearchQuery)
    }
    LaunchedEffect(showTagPopup) {
        if (showTagPopup) {
            kotlinx.coroutines.delay(50)
            tagSearchFocusRequester.requestFocus()
        }
    }
    val tagSuggestions = (remoteTagSuggestions + topTags.map { it.name })
        .filter { it.startsWith(tagSearchQuery, ignoreCase = true) }
        .distinctBy { it.lowercase() }
        .take(30)

    Scaffold(
        modifier = Modifier.systemBarsPadding(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Upload to Pixlit") },
                    actions = {
                        TextButton(onClick = onLogout) {
                            Text("Logout")
                        }
                    }
                )
                PrimaryScrollableTabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (selectedTab == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
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
                                onClick = { onShiftLeft(currentPage) },
                                enabled = currentPage > 0
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Shift Left")
                                Spacer(Modifier.width(4.dp))
                                Text("Shift Left")
                            }

                            OutlinedButton(
                                onClick = { onRemoveImage(currentPage) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, "Remove Photo")
                                Spacer(Modifier.width(4.dp))
                                Text("Remove")
                            }

                            OutlinedButton(
                                onClick = { onShiftRight(currentPage) },
                                enabled = currentPage < selectedImageUris.size - 1
                            ) {
                                Text("Shift Right")
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Shift Right")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OrderPreviewList(
                            selectedImageUris = selectedImageUris,
                            currentPage = currentPage,
                            maxPhotos = maxPhotos,
                            onImageClick = { index -> onPageChanged(index) }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    MetadataDisplay(
                        currentPage = currentPage,
                        totalImages = selectedImageUris.size,
                        originalMetadata = originalMetadata,
                        resizedMetadata = resizedMetadata,
                        isCalculatingResized = isCalculatingResized,
                        resizeTo8Mb = resizeTo8Mb
                    )

                    if (selectedImageUris.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onResizeToggled(!resizeTo8Mb) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = resizeTo8Mb,
                            onCheckedChange = { onResizeToggled(it) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Resize down to 8MB",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { galleryLauncher.launch("image/*") },
                        enabled = selectedImageUris.size < maxPhotos,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                selectedImageUris.isEmpty() -> "Select Photos from Gallery (up to 6)"
                                selectedImageUris.size < maxPhotos -> "Add More Photos (${selectedImageUris.size}/$maxPhotos)"
                                else -> "Maximum Photos Reached (6/6)"
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val cursor = captionState.selection.end.coerceIn(0, captionState.text.length)
                    val textBeforeCursor = captionState.text.substring(0, cursor)
                    val lineStart = textBeforeCursor.lastIndexOf('\n') + 1
                    val lineCharacterCount = cursor - lineStart
                    val cursorX = with(density) {
                        (16.dp.toPx() + lineCharacterCount * 8.dp.toPx()).toInt()
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = captionState,
                            onValueChange = { onCaptionChanged(it) },
                            label = { Text("Write a caption...") },
                            trailingIcon = {
                                TextButton(
                                    onClick = {
                                        onInsertHash()
                                        showTagPopup = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("#")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .bringIntoViewRequester(captionBringIntoViewRequester)
                                .onFocusChanged { captionFocused = it.isFocused },
                            maxLines = 10
                        )

                        if (showTagPopup && hashtagContext != null) {
                            Popup(
                                onDismissRequest = { showTagPopup = false },
                                popupPositionProvider = CaptionSuggestionPositionProvider(
                                    cursorX = cursorX
                                ),
                                properties = PopupProperties(
                                    focusable = true,
                                    dismissOnBackPress = true,
                                    dismissOnClickOutside = true
                                )
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .widthIn(min = 220.dp, max = 320.dp)
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState()),
                                    color = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    shadowElevation = 8.dp,
                                    tonalElevation = 4.dp,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        OutlinedTextField(
                                            value = tagSearchQuery,
                                            onValueChange = { tagSearchQuery = it.removePrefix("#") },
                                            placeholder = { Text("Search tags") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(tagSearchFocusRequester)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        if (tagSuggestions.isEmpty()) {
                                            Text(
                                                text = "No matching tags",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(8.dp)
                                            )
                                        } else {
                                            tagSuggestions.forEach { tag ->
                                                Text(
                                                    text = "#$tag",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            showTagPopup = false
                                                            onTagSuggestionClick(tag)
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 10.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val charCount = captionState.text.length
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
                            text = "pf: $charCount/$pfLimit",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (charCount > pfLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "bs: $charCount/$bsLimit",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (charCount > bsLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    PositionSelectionSection(
                        selectedPlace = selectedPlace,
                        customLocationName = customLocationName,
                        isExifAutoDetected = isExifAutoDetected,
                        isExifMissing = isExifMissing,
                        placeSearchQuery = placeSearchQuery,
                        placeSearchResults = placeSearchResults,
                        isSearchingPlaces = isSearchingPlaces,
                        onQueryChanged = onPlaceQueryChanged,
                        onSelectPlace = onSelectPlace,
                        onSetCustomLocation = onSetCustomLocation,
                        onClearLocation = onClearLocation
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    TagSelectionCloud(
                        topTags = topTags,
                        isLoadingTags = isLoadingTags,
                        onTagClick = { onTagClick(it) },
                        onRefreshClick = { onRefreshTags() }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    CollectionSelectionSection(
                        collections = userCollections,
                        selectedCollectionIds = selectedCollectionIds,
                        isLoadingCollections = isLoadingCollections,
                        onCollectionToggle = onCollectionToggle,
                        onCreateCollection = onCreateCollection
                    )
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (statusMessage != null) {
                            Text(
                                text = statusMessage,
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        Button(
                            onClick = { onUpload() },
                            enabled = !isUploading && selectedImageUris.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isUploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                val text = if (selectedImageUris.size > 1) "Upload ${selectedImageUris.size} Photos" else "Upload Photo"
                                Text(text)
                            }
                        }
                    }
                }
            }
        } else if (selectedTab == 1) {
            SocialScreen(
                onTagClick = {
                    onSocialTagClick(it)
                    selectedTab = 0
                },
                modifier = Modifier.padding(padding)
            )
        } else if (selectedTab == 2) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefreshRecentPosts,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                RecentPostsSection(
                    statuses = recentStatuses.take(visibleRecentPostCount),
                    onCopyPost = {
                        onCopyPost(it)
                        selectedTab = 0
                    },
                    onLoadMore = onLoadMorePosts,
                    isLoadingMore = isLoadingMorePosts,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            }
        } else if (selectedTab == 3) {
            // Debug Tab
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = "Fetched Posts (${recentStatuses.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cache", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = onRefreshCacheSummary) {
                                Text("Refresh")
                            }
                        }
                        cacheSummary.forEach { cache ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(cache.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = cache.entityCount.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = onSimulateNotification,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Simulate Art Show Notification")
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (recentStatuses.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isLoadingTags) "Loading posts..." else "No posts retrieved (tap refresh in Upload tab to fetch)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(recentStatuses) { status ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    val statusId = status.id?.toSafeString() ?: "Unknown ID"
                                    val tagsList = status.tags?.mapNotNull { it.name }?.filter { it.isNotBlank() } ?: emptyList()

                                    Text("ID: $statusId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    status.content?.let { if (it.isNotBlank()) Text("Content: $it", style = MaterialTheme.typography.bodySmall) }
                                    status.text?.let { if (it.isNotBlank()) Text("Text: $it", style = MaterialTheme.typography.bodySmall) }
                                    status.description?.let { if (it.isNotBlank()) Text("Description: $it", style = MaterialTheme.typography.bodySmall) }
                                    status.spoilerText?.let { if (it.isNotBlank()) Text("Spoiler: $it", style = MaterialTheme.typography.bodySmall) }
                                    
                                    if (tagsList.isNotEmpty()) {
                                        Text("Tags: ${tagsList.joinToString { "#$it" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            SettingsContent(reminderPreferences, reminderScheduler, Modifier.padding(padding))
        }
    }
}

@Composable
private fun SettingsContent(
    preferences: ReminderPreferences?,
    scheduler: ReminderScheduler?,
    modifier: Modifier = Modifier
) {
    if (preferences == null || scheduler == null) return
    var friday by remember { mutableStateOf(preferences.friday) }
    var saturday by remember { mutableStateOf(preferences.saturday) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("BlueSkyArtShow reminders", style = MaterialTheme.typography.headlineSmall)
        Text("Reminders are always on. Times use your device timezone.", style = MaterialTheme.typography.bodyMedium)
        ReminderTimeRow("Friday", friday) { time -> friday = time; preferences.friday = time; scheduler.rescheduleAll() }
        ReminderTimeRow("Saturday", saturday) { time -> saturday = time; preferences.saturday = time; scheduler.rescheduleAll() }
    }
}

@Composable
private fun ReminderTimeRow(label: String, time: LocalTime, onChanged: (LocalTime) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label reminder")
        OutlinedButton(onClick = { TimePickerDialog(context, { _, hour, minute -> onChanged(LocalTime.of(hour, minute)) }, time.hour, time.minute, true).show() }) {
            Text(String.format("%02d:%02d", time.hour, time.minute))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun UploadScreenPreview() {
    PixlitTheme {
        UploadContent(
            selectedImageUris = listOf(Uri.EMPTY, Uri.EMPTY),
            captionState = TextFieldValue("Check out my new photos!"),
            resizeTo8Mb = true,
            isUploading = false,
            statusMessage = null,
            isError = false,
            topTags = listOf(
                TagCount("pixelfed", 21),
                TagCount("android", 14),
                TagCount("kotlin", 8)
            ),
            recentStatuses = emptyList(),
            isLoadingTags = false,
            currentPage = 0,
            originalMetadata = ImageMetadata(1024 * 1024 * 5, 3000, 2000),
            resizedMetadata = null,
            isCalculatingResized = false
        )
    }
}
