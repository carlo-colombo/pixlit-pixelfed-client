package ovh.litapp.pixlit.ui.upload

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.api.toSafeString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.utils.ImageMetadata
import ovh.litapp.pixlit.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun UploadScreen(
    repository: PixelfedRepository,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var selectedImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var captionState by remember { mutableStateOf(TextFieldValue("")) }
    var resizeTo8Mb by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    var topTags by remember { mutableStateOf<List<String>>(repository.getDefaultStaticTags()) }
    var recentStatuses by remember { mutableStateOf<List<StatusItem>>(emptyList()) }
    var isLoadingTags by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Upload", "Debug")

    var originalMetadata by remember { mutableStateOf<ImageMetadata?>(null) }
    var resizedMetadata by remember { mutableStateOf<ImageMetadata?>(null) }
    var isCalculatingResized by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val maxPhotos = 6
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { selectedImageUris.size }
    )

    val currentFocusedUri = selectedImageUris.getOrNull(pagerState.currentPage)

    fun fetchTags(forceRefresh: Boolean = false) {
        scope.launch {
            isLoadingTags = true
            val result = repository.getUserTopTagsAndPosts(forceRefresh = forceRefresh)
            result.fold(
                onSuccess = { data ->
                    Log.d("UploadScreen", "fetchTags success: loaded ${data.topTags.size} tags and ${data.statuses.size} statuses")
                    topTags = if (data.topTags.isNotEmpty()) data.topTags else repository.getDefaultStaticTags()
                    if (data.statuses.isNotEmpty()) {
                        recentStatuses = data.statuses
                    }
                },
                onFailure = { ex ->
                    Log.e("UploadScreen", "fetchTags failure: ${ex.localizedMessage ?: ex.message ?: ex.toString()}", ex)
                    if (topTags.isEmpty()) {
                        Log.d("UploadScreen", "fetchTags: Falling back to default static tags")
                        topTags = repository.getDefaultStaticTags()
                    }
                }
            )
            isLoadingTags = false
        }
    }

    LaunchedEffect(Unit) {
        fetchTags(forceRefresh = false)
    }

    fun insertTagAtCursor(tagString: String) {
        // tagString is like "#photography (12)"
        val tagName = tagString.split(" ").firstOrNull()?.removePrefix("#") ?: ""
        if (tagName.isEmpty()) return

        val tagToInsert = "#$tagName "
        val currentText = captionState.text
        val selection = captionState.selection
        val start = selection.min.coerceAtLeast(0)
        val end = selection.max.coerceAtLeast(0)

        val newText = currentText.substring(0, start) + tagToInsert + currentText.substring(end)
        val newCursorPos = start + tagToInsert.length

        captionState = TextFieldValue(
            text = newText,
            selection = TextRange(newCursorPos)
        )
    }

    LaunchedEffect(currentFocusedUri) {
        val uri = currentFocusedUri
        if (uri != null) {
            originalMetadata = withContext(Dispatchers.IO) {
                ImageUtils.getImageMetadata(context, uri)
            }
        } else {
            originalMetadata = null
            resizedMetadata = null
        }
    }

    LaunchedEffect(currentFocusedUri, resizeTo8Mb, originalMetadata) {
        val uri = currentFocusedUri
        val meta = originalMetadata
        if (uri != null && resizeTo8Mb && meta != null && meta.sizeBytes > ImageUtils.MAX_BYTES_8MB) {
            isCalculatingResized = true
            val resizedFile = withContext(Dispatchers.IO) {
                ImageUtils.resizeImageDownToMaxBytes(context, uri, ImageUtils.MAX_BYTES_8MB)
            }
            resizedMetadata = if (resizedFile != null) {
                ImageUtils.getFileMetadata(resizedFile)
            } else {
                null
            }
            isCalculatingResized = false
        } else {
            resizedMetadata = null
            isCalculatingResized = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val combined = (selectedImageUris + uris).take(maxPhotos)
            selectedImageUris = combined
            statusMessage = null
        }
    }

    fun removeImageAt(index: Int) {
        if (index in selectedImageUris.indices) {
            val newList = selectedImageUris.toMutableList()
            newList.removeAt(index)
            selectedImageUris = newList

            scope.launch {
                val newTarget = when {
                    newList.isEmpty() -> 0
                    index >= newList.size -> newList.size - 1
                    else -> index
                }
                if (newList.isNotEmpty()) {
                    pagerState.scrollToPage(newTarget)
                }
            }
        }
    }

    fun shiftLeft(index: Int) {
        if (index > 0 && index < selectedImageUris.size) {
            val newList = selectedImageUris.toMutableList()
            val temp = newList[index]
            newList[index] = newList[index - 1]
            newList[index - 1] = temp
            selectedImageUris = newList
            scope.launch {
                pagerState.scrollToPage(index - 1)
            }
        }
    }

    fun shiftRight(index: Int) {
        if (index >= 0 && index < selectedImageUris.size - 1) {
            val newList = selectedImageUris.toMutableList()
            val temp = newList[index]
            newList[index] = newList[index + 1]
            newList[index + 1] = temp
            selectedImageUris = newList
            scope.launch {
                pagerState.scrollToPage(index + 1)
            }
        }
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (selectedImageUris.isNotEmpty()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        Image(
                            painter = rememberAsyncImagePainter(selectedImageUris[page]),
                            contentDescription = "Selected Photo ${page + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    if (selectedImageUris.size > 1) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(selectedImageUris.size) { page ->
                                val color = if (pagerState.currentPage == page) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "No images selected",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedImageUris.isNotEmpty()) {
                val currentIndex = pagerState.currentPage
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { shiftLeft(currentIndex) },
                        enabled = currentIndex > 0
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Shift Left"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Shift Left")
                    }

                    OutlinedButton(
                        onClick = { removeImageAt(currentIndex) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove Photo"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Remove")
                    }

                    OutlinedButton(
                        onClick = { shiftRight(currentIndex) },
                        enabled = currentIndex < selectedImageUris.size - 1
                    ) {
                        Text("Shift Right")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Shift Right"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
                val thumbnailSize = screenWidthDp * 0.15f

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Upload Order Preview (${selectedImageUris.size}/$maxPhotos)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(selectedImageUris) { index, uri ->
                            val isFocused = index == pagerState.currentPage
                            Box(
                                modifier = Modifier
                                    .size(thumbnailSize)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = if (isFocused) 3.dp else 1.dp,
                                        color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        scope.launch {
                                            pagerState.scrollToPage(index)
                                        }
                                    }
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(uri),
                                    contentDescription = "Thumbnail ${index + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(bottomEnd = 6.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            if (selectedImageUris.isNotEmpty() && originalMetadata != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val meta = originalMetadata!!
                        Text(
                            text = "Photo ${pagerState.currentPage + 1} of ${selectedImageUris.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (resizeTo8Mb && meta.sizeBytes > ImageUtils.MAX_BYTES_8MB) {
                            Text(
                                text = "Original: ${meta.formatFileSize()} (${meta.formatDimensions()})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isCalculatingResized) {
                                Text(
                                    text = "Resized: Calculating...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (resizedMetadata != null) {
                                val rMeta = resizedMetadata!!
                                Text(
                                    text = "Resized: ${rMeta.formatFileSize()} (${rMeta.formatDimensions()})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Text(
                                text = "Size: ${meta.formatFileSize()} (${meta.formatDimensions()})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { resizeTo8Mb = !resizeTo8Mb }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = resizeTo8Mb,
                    onCheckedChange = { resizeTo8Mb = it }
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
                onValueChange = { captionState = it },
                label = { Text("Write a caption...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
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
                        onClick = { fetchTags(forceRefresh = true) },
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
                                onClick = { insertTagAtCursor(tag) },
                                label = { Text(tag) }
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
                onClick = {
                    if (selectedImageUris.isEmpty()) {
                        statusMessage = "Please select at least one photo"
                        isError = true
                        return@Button
                    }

                    isUploading = true
                    statusMessage = null
                    isError = false

                    scope.launch {
                        val result = repository.uploadPhotosAndCreateStatus(
                            imageUris = selectedImageUris,
                            caption = captionState.text,
                            resizeTo8Mb = resizeTo8Mb
                        )
                        isUploading = false
                        result.fold(
                            onSuccess = {
                                statusMessage = "Successfully uploaded ${selectedImageUris.size} photo(s) to Pixlit!"
                                isError = false
                                selectedImageUris = emptyList()
                                captionState = TextFieldValue("")
                            },
                            onFailure = { ex ->
                                val msg = ex.localizedMessage ?: ex.message ?: ex.toString()
                                statusMessage = "Upload failed: $msg"
                                isError = true
                                Log.e("UploadScreen", "Upload failed: $msg", ex)
                            }
                        )
                    }
                },
                enabled = !isUploading && selectedImageUris.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (selectedImageUris.size > 1) "Upload ${selectedImageUris.size} Photos" else "Upload Photo")
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
                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    val statusId = status.id?.toSafeString() ?: "Unknown ID"
                                    val contentText = status.content
                                    val textVal = status.text
                                    val descVal = status.description
                                    val spoilerVal = status.spoilerText
                                    val tagsList = status.tags?.mapNotNull { it.name }?.filter { it.isNotBlank() } ?: emptyList()

                                    Text(
                                        text = "ID: $statusId",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (!contentText.isNullOrBlank()) {
                                        Text(
                                            text = "Content: $contentText",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (!textVal.isNullOrBlank()) {
                                        Text(
                                            text = "Text: $textVal",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (!descVal.isNullOrBlank()) {
                                        Text(
                                            text = "Description: $descVal",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (!spoilerVal.isNullOrBlank()) {
                                        Text(
                                            text = "Spoiler: $spoilerVal",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (contentText.isNullOrBlank() && textVal.isNullOrBlank() && descVal.isNullOrBlank() && spoilerVal.isNullOrBlank()) {
                                        Text(
                                            text = "[No text / description]",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (tagsList.isNotEmpty()) {
                                        Text(
                                            text = "Tags: ${tagsList.joinToString { "#$it" }}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
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
