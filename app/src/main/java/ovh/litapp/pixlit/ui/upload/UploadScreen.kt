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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ovh.litapp.pixlit.data.api.toSafeString
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.ui.upload.components.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UploadScreen(
    repository: PixelfedRepository,
    onLogout: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel()
) {
    val selectedImageUris by viewModel.selectedImageUris.collectAsState()
    val captionState by viewModel.captionState.collectAsState()
    val resizeTo8Mb by viewModel.resizeTo8Mb.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isError by viewModel.isError.collectAsState()
    val topTags by viewModel.topTags.collectAsState()
    val recentStatuses by viewModel.recentStatuses.collectAsState()
    val isLoadingTags by viewModel.isLoadingTags.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val originalMetadata by viewModel.originalMetadata.collectAsState()
    val resizedMetadata by viewModel.resizedMetadata.collectAsState()
    val isCalculatingResized by viewModel.isCalculatingResized.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Upload", "Debug")

    val maxPhotos = 6
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { selectedImageUris.size }
    )

    // Sync PagerState with ViewModel
    LaunchedEffect(pagerState.currentPage) {
        viewModel.onPageChanged(pagerState.currentPage)
    }

    // Sync ViewModel's currentPage back to PagerState if it changes (e.g. on reorder/remove)
    LaunchedEffect(currentPage) {
        if (currentPage != pagerState.currentPage && currentPage < selectedImageUris.size) {
            pagerState.scrollToPage(currentPage)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        viewModel.addImages(uris)
    }

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
                TabRow(selectedTabIndex = selectedTab) {
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
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
                            onClick = { viewModel.shiftLeft(currentPage) },
                            enabled = currentPage > 0
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Shift Left")
                            Spacer(Modifier.width(4.dp))
                            Text("Shift Left")
                        }

                        OutlinedButton(
                            onClick = { viewModel.removeImageAt(currentPage) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, "Remove Photo")
                            Spacer(Modifier.width(4.dp))
                            Text("Remove")
                        }

                        OutlinedButton(
                            onClick = { viewModel.shiftRight(currentPage) },
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
                        onImageClick = { index -> viewModel.onPageChanged(index) }
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
                        .clickable { viewModel.onResizeToggled(!resizeTo8Mb) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = resizeTo8Mb,
                        onCheckedChange = { viewModel.onResizeToggled(it) }
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

                OutlinedTextField(
                    value = captionState,
                    onValueChange = { viewModel.onCaptionChanged(it) },
                    label = { Text("Write a caption...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    maxLines = 5
                )

                Spacer(modifier = Modifier.height(8.dp))

                TagSelectionCloud(
                    topTags = topTags,
                    isLoadingTags = isLoadingTags,
                    onTagClick = { viewModel.insertTag(it) },
                    onRefreshClick = { viewModel.fetchTags(forceRefresh = true) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (statusMessage != null) {
                    Text(
                        text = statusMessage!!,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Button(
                    onClick = { viewModel.upload() },
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
        } else {
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
        }
    }
}
