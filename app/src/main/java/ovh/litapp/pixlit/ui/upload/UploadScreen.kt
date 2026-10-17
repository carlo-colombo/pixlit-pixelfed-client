package ovh.litapp.pixlit.ui.upload

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import ovh.litapp.pixlit.BuildConfig
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
import ovh.litapp.pixlit.ui.about.AboutSection

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
        onInsertImage = { index, uri -> viewModel.insertImageAt(index, uri) },
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
    onInsertImage: (Int, Uri) -> Unit = { _, _ -> },
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
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    // The Debug tab is flanked by Upload/Social/Recent (0-2) and Settings, so omitting it shifts
    // Settings from index 4 to 3. The dispatch below keys off BuildConfig.DEBUG_TOOLS as well as the
    // index, so prod's index 3 resolves to Settings and R8 can delete the dead Debug branch.
    val tabs = buildList {
        add(tabUpload)
        add(tabSocial)
        add(tabRecent)
        if (BuildConfig.DEBUG_TOOLS) add(tabDebug)
        add(tabSettings)
    }

    val maxPhotos = 6
    val uploadScrollState = rememberScrollState()
    var showTagPopup by remember { mutableStateOf(false) }
    val tagSearchFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val removedMessage = stringResource(R.string.upload_removed_snackbar)
    val undoLabel = stringResource(R.string.action_undo)
    val tabUpload = stringResource(R.string.tab_upload)
    val tabSocial = stringResource(R.string.tab_social)
    val tabRecent = stringResource(R.string.tab_recent)
    val tabDebug = stringResource(R.string.tab_debug)
    val tabSettings = stringResource(R.string.tab_settings)
    // Tracks the tab the previously-rendered composition showed, so restoring a saved tab does not
    // re-fire the Debug tab's refresh side effect.
    var lastSelectedTab by rememberSaveable { mutableStateOf(selectedTab) }

    // System back returns to the Upload tab instead of leaving the app; on Upload it stays disabled
    // so back still exits.
    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

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
        if (BuildConfig.DEBUG_TOOLS && selectedTab == 3 && lastSelectedTab != 3) onRefreshCacheSummary()
        lastSelectedTab = selectedTab
    }

    // Upload success/failure is transient, so it is surfaced through the scaffold's snackbar host
    // instead of lingering as inline text. Errors are dismissable and stay a little longer.
    LaunchedEffect(statusMessage, isError) {
        if (statusMessage != null) {
            snackbarHostState.showSnackbar(
                message = statusMessage,
                withDismissAction = isError,
                duration = if (isError) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxPhotos)
    ) { uris ->
        if (uris.isNotEmpty()) onAddImages(uris)
    }

    val hashtagContext = findHashtagContext(captionState)
    var tagSearchQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(hashtagContext?.start, hashtagContext?.end, hashtagContext?.query) {
        // Only sync from the caption; a null context (e.g. after rotation) must not clobber the
        // saveable query.
        if (hashtagContext != null) tagSearchQuery = hashtagContext.query
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

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.logout_confirm_title)) },
            text = { Text(stringResource(R.string.logout_confirm_message)) },
            confirmButton = {
                Button(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) {
                    Text(stringResource(R.string.logout_confirm_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.navigationBarsPadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.upload_header_title)) })
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
                                    onRemoveImage(index)
                                    if (removedUri != null) {
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = removedMessage,
                                                actionLabel = undoLabel,
                                                duration = SnackbarDuration.Short
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                onInsertImage(index, removedUri)
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
                                onClick = { onShiftRight(currentPage) },
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
                            .toggleable(
                                value = resizeTo8Mb,
                                role = Role.Checkbox,
                                enabled = !isUploading,
                                onValueChange = onResizeToggled
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

                    var captionLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    val caretOffset = captionState.selection.end
                    // Anchor the hashtag popup to the measured caret instead of an assumed glyph
                    // width; before the first layout it falls back to the field's leading edge.
                    val cursorX = remember(captionLayout, caretOffset) {
                        val layout = captionLayout
                        if (layout == null) {
                            0
                        } else {
                            val offset = caretOffset.coerceIn(0, layout.layoutInput.text.length)
                            layout.getHorizontalPosition(offset, usePrimaryDirection = true).toInt()
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = captionState,
                            onValueChange = { onCaptionChanged(it) },
                            label = { Text(stringResource(R.string.upload_caption_label)) },
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
                                .heightIn(min = 120.dp),
                            maxLines = 10,
                            onTextLayout = { captionLayout = it }
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
                                            placeholder = { Text(stringResource(R.string.upload_caption_search_tags)) },
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(tagSearchFocusRequester)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        if (tagSuggestions.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.upload_no_matching_tags),
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
        } else if (BuildConfig.DEBUG_TOOLS && selectedTab == 3) {
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
            SettingsContent(
                preferences = reminderPreferences,
                scheduler = reminderScheduler,
                onLogout = { showLogoutConfirm = true },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun SettingsContent(
    preferences: ReminderPreferences?,
    scheduler: ReminderScheduler?,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (preferences != null && scheduler != null) {
            ReminderSettings(preferences, scheduler)
        }
        AboutSection()
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text(stringResource(R.string.action_logout))
        }
    }
}

@Composable
private fun ReminderSettings(preferences: ReminderPreferences, scheduler: ReminderScheduler) {
    var friday by remember { mutableStateOf(preferences.friday) }
    var saturday by remember { mutableStateOf(preferences.saturday) }
    Text("BlueSkyArtShow reminders", style = MaterialTheme.typography.headlineSmall)
    Text("Reminders are always on. Times use your device timezone.", style = MaterialTheme.typography.bodyMedium)
    NotificationPermissionNotice()
    ReminderTimeRow("Friday", friday) { time -> friday = time; preferences.friday = time; scheduler.rescheduleAll() }
    ReminderTimeRow("Saturday", saturday) { time -> saturday = time; preferences.saturday = time; scheduler.rescheduleAll() }
}

/**
 * Prompts for POST_NOTIFICATIONS only here, where enabling a reminder is the action that needs it,
 * and surfaces the denied state (read from the system, not from a cached "already asked" flag) so a
 * denied permission does not make the reminder fail invisibly. Tapping again re-prompts, and the
 * system-settings route lets a permanent denial be reversed without reinstalling.
 */
@Composable
private fun NotificationPermissionNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var showRationale by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        enabled = granted || NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    if (enabled) {
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.notifications_disabled_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.notifications_disabled_message),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showRationale = true }) {
                    Text(stringResource(R.string.notifications_enable))
                }
                TextButton(onClick = { context.openNotificationSettings() }) {
                    Text(stringResource(R.string.notifications_open_settings))
                }
            }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.notifications_rationale_title)) },
            text = { Text(stringResource(R.string.notifications_rationale_message)) },
            confirmButton = {
                Button(onClick = {
                    showRationale = false
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text(stringResource(R.string.action_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text(stringResource(R.string.action_not_now))
                }
            }
        )
    }
}

private fun Context.openNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
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

@Preview(showBackground = true)
@Composable
fun SettingsContentPreview() {
    PixlitTheme {
        SettingsContent(
            preferences = null,
            scheduler = null,
            onLogout = {}
        )
    }
}