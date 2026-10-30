package ovh.litapp.pixlit.ui.upload

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import ovh.litapp.pixlit.BuildConfig
import ovh.litapp.pixlit.R
import ovh.litapp.pixlit.data.reminder.ReminderPreferences
import ovh.litapp.pixlit.data.reminder.ReminderScheduler
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.data.repository.TagCount
import ovh.litapp.pixlit.ui.social.SocialScreen
import ovh.litapp.pixlit.ui.theme.PixlitTheme
import ovh.litapp.pixlit.utils.ImageMetadata

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
    val state by viewModel.uiState.collectAsState()

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

    val callbacks = remember(viewModel, onLogout) {
        UploadCallbacks(
            onLogout = onLogout,
            onPlaceQueryChanged = viewModel::onPlaceSearchQueryChanged,
            onSelectPlace = viewModel::selectPlace,
            onSetCustomLocation = viewModel::setCustomLocation,
            onClearLocation = viewModel::clearLocation,
            onCollectionToggle = viewModel::toggleCollectionSelection,
            onRefreshCollections = viewModel::fetchCollections,
            onPageChanged = viewModel::onPageChanged,
            onAddImages = viewModel::addImages,
            onShiftLeft = viewModel::shiftLeft,
            onShiftRight = viewModel::shiftRight,
            onRemoveImage = viewModel::removeImageAt,
            onInsertImage = { index, uri -> viewModel.insertImageAt(index, uri) },
            onResizeToggled = viewModel::onResizeToggled,
            onCaptionChanged = viewModel::onCaptionChanged,
            onInsertHash = viewModel::insertHash,
            onTagSearchQueryChanged = viewModel::searchTagSuggestions,
            onRefreshCacheSummary = viewModel::refreshCacheSummary,
            onTagSuggestionClick = viewModel::insertTagSuggestion,
            onTagClick = viewModel::insertTag,
            onSocialTagClick = { tags -> tags.forEach(viewModel::insertTag) },
            onCopyPost = { text -> viewModel.onCaptionChanged(TextFieldValue(text)) },
            onLoadMorePosts = viewModel::loadMoreRecentPosts,
            onRefreshRecentPosts = viewModel::refreshRecentPosts,
            onRefreshTags = { viewModel.fetchTags(forceRefresh = true) },
            onUpload = viewModel::upload
        )
    }

    UploadContent(
        state = state,
        callbacks = callbacks,
        reminderPreferences = reminderPreferences,
        reminderScheduler = reminderScheduler,
        onSimulateNotification = onSimulateNotification
    )
}

/**
 * The upload screen's scaffold and tab host (REF-01/REF-02). It owns only cross-tab state
 * (`selectedTab`, the pager handle, the snackbar host) and dispatches to one stateless tab body per
 * index.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UploadContent(
    state: UploadUiState,
    callbacks: UploadCallbacks = UploadCallbacks(),
    reminderPreferences: ReminderPreferences? = null,
    reminderScheduler: ReminderScheduler? = null,
    onSimulateNotification: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    // The Debug tab is flanked by Upload/Social/Recent (0-2) and Settings, so omitting it shifts
    // Settings from index 4 to 3. The dispatch below keys off BuildConfig.DEBUG_TOOLS as well as the
    // index, so prod's index 3 resolves to Settings and R8 can delete the dead Debug branch.
    val tabUpload = stringResource(R.string.tab_upload)
    val tabSocial = stringResource(R.string.tab_social)
    val tabRecent = stringResource(R.string.tab_recent)
    val tabDebug = stringResource(R.string.tab_debug)
    val tabSettings = stringResource(R.string.tab_settings)
    val tabs = buildList {
        add(tabUpload)
        add(tabSocial)
        add(tabRecent)
        if (BuildConfig.DEBUG_TOOLS) add(tabDebug)
        add(tabSettings)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    // Tracks the tab the previously-rendered composition showed, so restoring a saved tab does not
    // re-fire the Debug tab's refresh side effect.
    var lastSelectedTab by rememberSaveable { mutableStateOf(selectedTab) }

    // System back returns to the Upload tab instead of leaving the app; on Upload it stays disabled
    // so back still exits.
    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { state.selectedImageUris.size }
    )

    // Sync PagerState with outer currentPage
    LaunchedEffect(pagerState.currentPage) {
        callbacks.onPageChanged(pagerState.currentPage)
    }

    LaunchedEffect(state.currentPage) {
        if (state.currentPage != pagerState.currentPage && state.currentPage < state.selectedImageUris.size) {
            pagerState.scrollToPage(state.currentPage)
        }
    }

    LaunchedEffect(selectedTab) {
        if (BuildConfig.DEBUG_TOOLS && selectedTab == 3 && lastSelectedTab != 3) callbacks.onRefreshCacheSummary()
        lastSelectedTab = selectedTab
    }

    // Upload success/failure is transient, so it is surfaced through the scaffold's snackbar host
    // instead of lingering as inline text. Errors are dismissable and stay a little longer.
    LaunchedEffect(state.statusMessage, state.isError) {
        val message = state.statusMessage
        if (message != null) {
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = state.isError,
                duration = if (state.isError) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.logout_confirm_title)) },
            text = { Text(stringResource(R.string.logout_confirm_message)) },
            confirmButton = {
                Button(onClick = {
                    showLogoutConfirm = false
                    callbacks.onLogout()
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
            UploadTabContent(
                state = state,
                callbacks = callbacks,
                pagerState = pagerState,
                snackbarHostState = snackbarHostState,
                modifier = Modifier.padding(padding)
            )
        } else if (selectedTab == 1) {
            SocialScreen(
                onTagClick = {
                    callbacks.onSocialTagClick(it)
                    selectedTab = 0
                },
                modifier = Modifier.padding(padding)
            )
        } else if (selectedTab == 2) {
            RecentPostsTabContent(
                state = state,
                callbacks = callbacks,
                onCopyPost = {
                    callbacks.onCopyPost(it)
                    selectedTab = 0
                },
                modifier = Modifier.padding(padding)
            )
        } else if (BuildConfig.DEBUG_TOOLS && selectedTab == 3) {
            DebugTabContent(
                state = state,
                callbacks = callbacks,
                onSimulateNotification = onSimulateNotification,
                modifier = Modifier.padding(padding)
            )
        } else {
            SettingsTabContent(
                preferences = reminderPreferences,
                scheduler = reminderScheduler,
                onLogout = { showLogoutConfirm = true },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun UploadScreenPreview() {
    PixlitTheme {
        UploadContent(
            state = UploadUiState(
                selectedImageUris = listOf(Uri.EMPTY, Uri.EMPTY),
                caption = TextFieldValue("Check out my new photos!"),
                resizeTo8Mb = true,
                topTags = listOf(
                    TagCount("pixelfed", 21),
                    TagCount("android", 14),
                    TagCount("kotlin", 8)
                ),
                originalMetadata = ImageMetadata(1024 * 1024 * 5, 3000, 2000)
            )
        )
    }
}
