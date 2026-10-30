package ovh.litapp.pixlit.ui.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonPrimitive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ovh.litapp.pixlit.data.Fetch
import ovh.litapp.pixlit.data.api.MAX_COLLECTIONS_PER_POST
import ovh.litapp.pixlit.data.api.PlaceItem
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.repository.CacheSummaryItem
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.data.repository.TagCount
import ovh.litapp.pixlit.data.work.UploadJob
import ovh.litapp.pixlit.data.work.UploadJobState
import ovh.litapp.pixlit.data.work.UploadJobStatus
import ovh.litapp.pixlit.data.work.UploadScheduler
import ovh.litapp.pixlit.utils.ImageMetadata
import ovh.litapp.pixlit.utils.ImageUtils
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PixelfedRepository,
    private val uploadScheduler: UploadScheduler,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /**
     * The screen's single source of truth (REF-01). Every public flow that used to exist is now a
     * field of [UploadUiState]; writers go through [MutableStateFlow.update] so a change is one
     * `copy` rather than a scattered write.
     */
    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    private var tagSearchJob: Job? = null
    private var pendingTagQuery: String? = null

    private var activeUploadId: String? = null
    private var uploadObservationJob: Job? = null

    private var placeSearchJob: Job? = null
    private var exifDetectionJob: Job? = null
    private var isUserLocationSelectionManual = false

    /** URI whose metadata is the one currently allowed to publish; superseded work is ignored. */
    private var metadataRequestUri: Uri? = null

    init {
        restoreDraft()

        // Fill the tag section with the static onboarding asset off the main thread;
        // fetchTags() replaces it with live counts when the account request lands.
        viewModelScope.launch {
            val staticTagCounts = repository.getDefaultStaticTagCounts()
            if (_uiState.value.topTags.isEmpty()) {
                _uiState.update { it.copy(topTags = staticTagCounts) }
            }
        }

        // Load the real account history so the recent-posts section is useful on first render.
        fetchTags(forceRefresh = true)
        fetchCollections()
        refreshCacheSummary()

        // Sync metadata when current page or URIs or resize setting changes.
        // collectLatest cancels the superseded page instead of letting it write stale metadata.
        viewModelScope.launch {
            _uiState
                .map { Triple(it.currentPage, it.selectedImageUris, it.resizeTo8Mb) }
                .distinctUntilChanged()
                .collectLatest { (page, uris, resize) ->
                    val uri = uris.getOrNull(page)
                    if (uri != null) {
                        updateMetadata(uri, resize)
                    } else {
                        clearMetadata()
                    }
                }
        }

        _uiState
            .map { it.selectedImageUris }
            .distinctUntilChanged()
            .onEach { uris ->
                if (uris.isEmpty()) {
                    _uiState.update { it.copy(isExifMissing = false) }
                    if (!isUserLocationSelectionManual) {
                        _uiState.update {
                            it.copy(
                                selectedPlace = null,
                                customLocationName = null,
                                isExifAutoDetected = false
                            )
                        }
                    }
                } else if (!isUserLocationSelectionManual &&
                    _uiState.value.selectedPlace == null &&
                    _uiState.value.customLocationName == null
                ) {
                    detectExifLocation(uris)
                }
            }
            .launchIn(viewModelScope)

        persistDraft()
    }

    fun detectExifLocation(uris: List<Uri>) {
        exifDetectionJob?.cancel()
        exifDetectionJob = viewModelScope.launch {
            _uiState.update { it.copy(isDetectingExifLocation = true) }
            try {
                var foundExif = false
                for (uri in uris) {
                    try {
                        val coords = withContext(Dispatchers.IO) {
                            ImageUtils.extractExifLocation(context, uri)
                        } ?: continue
                        foundExif = true
                        _uiState.update { it.copy(isExifMissing = false) }
                        val (lat, lng) = coords
                        val cityName = withContext(Dispatchers.IO) {
                            ImageUtils.cityNameFromLocation(context, lat, lng)
                        }
                        val locationName = cityName ?: String.format(
                            java.util.Locale.US,
                            "%.2f, %.2f",
                            lat,
                            lng
                        )
                        // Re-assert the manual-selection guard on Main after the Geocoder round-trip:
                        // the user may have picked or typed a location while it was in flight.
                        withContext(Dispatchers.Main.immediate) {
                            if (isUserLocationSelectionManual ||
                                _uiState.value.selectedPlace != null ||
                                _uiState.value.customLocationName != null
                            ) {
                                return@withContext
                            }
                            _uiState.update {
                                it.copy(
                                    customLocationName = locationName,
                                    selectedPlace = null,
                                    isExifAutoDetected = true
                                )
                            }
                        }
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "EXIF location detection failed", e)
                    }
                }
                if (!foundExif && isActive) {
                    _uiState.update { it.copy(isExifMissing = true) }
                }
            } finally {
                _uiState.update { it.copy(isDetectingExifLocation = false) }
            }
        }
    }

    /**
     * [UploadUiState.isSearchingPlaces] flips to `true` before the debounce delay so the
     * composable's "Use … as location name" fallback stays hidden while the user is typing.
     */
    fun onPlaceSearchQueryChanged(query: String) {
        _uiState.update { it.copy(placeSearchQuery = query) }
        placeSearchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(placeSearchResults = emptyList(), isSearchingPlaces = false) }
            return
        }
        _uiState.update { it.copy(isSearchingPlaces = true) }
        placeSearchJob = viewModelScope.launch {
            delay(PLACE_SEARCH_DEBOUNCE_MS)
            // A failed search shows no results but is logged; it is not an empty success (DATA-05).
            when (val fetch = repository.searchPlaces(query = query.trim())) {
                is Fetch.Fresh -> _uiState.update { it.copy(placeSearchResults = fetch.value) }
                is Fetch.Cached -> _uiState.update { it.copy(placeSearchResults = fetch.value) }
                is Fetch.Failed -> {
                    Log.e(TAG, "place search failed", fetch.cause)
                    _uiState.update { it.copy(placeSearchResults = emptyList()) }
                }
            }
            _uiState.update { it.copy(isSearchingPlaces = false) }
        }
    }

    fun selectPlace(place: PlaceItem) {
        exifDetectionJob?.cancel()
        _uiState.update {
            it.copy(
                selectedPlace = place,
                customLocationName = null,
                isExifAutoDetected = false,
                placeSearchQuery = "",
                placeSearchResults = emptyList()
            )
        }
        isUserLocationSelectionManual = true
    }

    fun setCustomLocation(name: String) {
        if (name.isBlank()) {
            clearLocation()
            return
        }
        exifDetectionJob?.cancel()
        _uiState.update {
            it.copy(
                selectedPlace = null,
                customLocationName = name.trim(),
                isExifAutoDetected = false,
                placeSearchQuery = "",
                placeSearchResults = emptyList()
            )
        }
        isUserLocationSelectionManual = true
    }

    fun clearLocation() {
        exifDetectionJob?.cancel()
        _uiState.update {
            it.copy(
                selectedPlace = null,
                customLocationName = null,
                isExifAutoDetected = false,
                placeSearchQuery = "",
                placeSearchResults = emptyList()
            )
        }
        isUserLocationSelectionManual = true
    }

    /**
     * Loads metadata for [uri] in the collector's coroutine so [collectLatest] can cancel
     * superseded pages; [metadataRequestUri] gates every write against a stale completion. The
     * resized preview is the file the upload will send (UP-08), not a second resize.
     */
    private suspend fun updateMetadata(uri: Uri, resize: Boolean) {
        metadataRequestUri = uri
        try {
            val meta = withContext(Dispatchers.IO) {
                ImageUtils.getImageMetadata(context, uri)
            }
            if (metadataRequestUri != uri) return
            _uiState.update { it.copy(originalMetadata = meta) }

            if (resize && meta != null && meta.sizeBytes > ImageUtils.MAX_BYTES_8MB) {
                _uiState.update { it.copy(isCalculatingResized = true) }
                val prepared = repository.prepareImage(uri, resize = true).getOrNull()
                if (metadataRequestUri != uri) return
                _uiState.update {
                    it.copy(resizedMetadata = prepared?.let { preparedImage -> ImageUtils.getFileMetadata(preparedImage.file) })
                }
            } else {
                _uiState.update { it.copy(resizedMetadata = null) }
            }
        } finally {
            if (metadataRequestUri == uri) {
                _uiState.update { it.copy(isCalculatingResized = false) }
            }
        }
    }

    private fun clearMetadata() {
        metadataRequestUri = null
        _uiState.update {
            it.copy(originalMetadata = null, resizedMetadata = null, isCalculatingResized = false)
        }
    }

    fun fetchCollections() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCollections = true) }
            try {
                when (val fetch = repository.getUserCollections()) {
                    is Fetch.Fresh -> _uiState.update { it.copy(userCollections = fetch.value) }
                    is Fetch.Cached -> {
                        _uiState.update { it.copy(userCollections = fetch.value) }
                        warnOnce("Showing cached collections — the refresh failed")
                    }
                    is Fetch.Failed -> {
                        Log.e(TAG, "fetchCollections failure", fetch.cause)
                        reportError("Could not load your collections: ${fetch.cause.describe()}")
                    }
                }
            } finally {
                _uiState.update { it.copy(isLoadingCollections = false) }
            }
        }
    }

    fun toggleCollectionSelection(collectionId: String) {
        val selected = _uiState.value.selectedCollectionIds
        if (collectionId !in selected && selected.size >= MAX_COLLECTIONS_PER_POST) {
            // Pixelfed validates `collection_ids` as max:3 on status creation; a fourth selection
            // would 422 the post, so it is refused here rather than failing at upload time.
            warnOnce("A post can be added to at most $MAX_COLLECTIONS_PER_POST collections")
            return
        }
        _uiState.update { state ->
            val current = state.selectedCollectionIds.toMutableSet()
            if (current.contains(collectionId)) {
                current.remove(collectionId)
            } else {
                current.add(collectionId)
            }
            state.copy(selectedCollectionIds = current)
        }
    }

    fun refreshRecentPosts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                when (val fetch = repository.getUserTopTagsAndPosts(forceRefresh = true)) {
                    is Fetch.Fresh -> applyTagsAndPosts(fetch.value)
                    is Fetch.Cached -> {
                        applyTagsAndPosts(fetch.value)
                        warnOnce("Showing cached posts — the refresh failed")
                    }
                    is Fetch.Failed -> {
                        Log.e(TAG, "refreshRecentPosts failure", fetch.cause)
                        reportError("Refresh failed: ${fetch.cause.describe()}")
                    }
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun fetchTags(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTags = true) }
            try {
                when (val fetch = repository.getUserTopTagsAndPosts(forceRefresh = forceRefresh)) {
                    is Fetch.Fresh -> applyTagsAndPosts(fetch.value)
                    is Fetch.Cached -> {
                        applyTagsAndPosts(fetch.value)
                        warnOnce("Showing cached posts — the refresh failed")
                    }
                    is Fetch.Failed -> {
                        Log.e(TAG, "fetchTags failure", fetch.cause)
                        _uiState.update { it.copy(topTags = repository.getDefaultStaticTagCounts()) }
                        reportError("Could not load your tags and posts: ${fetch.cause.describe()}")
                    }
                }
            } finally {
                _uiState.update { it.copy(isLoadingTags = false) }
            }
        }
    }

    private suspend fun applyTagsAndPosts(data: PixelfedRepository.TagsAndPosts) {
        val topTags = if (data.topTags.isNotEmpty()) {
            data.topTags
        } else {
            repository.getDefaultStaticTagCounts()
        }
        _uiState.update { state ->
            var next = state.copy(topTags = topTags)
            if (data.statuses.isNotEmpty()) {
                next = next.copy(recentStatuses = data.statuses, visibleRecentPostCount = PAGE_SIZE)
            }
            next
        }
    }

    fun loadMoreRecentPosts() {
        if (_uiState.value.isLoadingMorePosts) return
        if (_uiState.value.visibleRecentPostCount < _uiState.value.recentStatuses.size) {
            _uiState.update { it.copy(visibleRecentPostCount = it.visibleRecentPostCount + PAGE_SIZE) }
            return
        }

        val lastId = _uiState.value.recentStatuses.lastOrNull()?.getIdString() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMorePosts = true) }
            repository.getMoreUserStatuses(lastId).onSuccess { moreStatuses ->
                _uiState.update { state ->
                    val existingIds = state.recentStatuses.mapNotNull { it.getIdString() }.toSet()
                    val newStatuses = moreStatuses.filter { it.getIdString() !in existingIds }
                    state.copy(
                        recentStatuses = state.recentStatuses + newStatuses,
                        visibleRecentPostCount = state.visibleRecentPostCount + PAGE_SIZE
                    )
                }
            }
            _uiState.update { it.copy(isLoadingMorePosts = false) }
        }
    }

    fun addImages(uris: List<Uri>) {
        val current = _uiState.value.selectedImageUris
        // A pick can repeat a URI (e.g. the same photo tapped twice) or re-send one already selected.
        val newUris = uris.distinct().filterNot { it in current }
        val remainingSlots = (MAX_PHOTOS - current.size).coerceAtLeast(0)
        val accepted = newUris.take(remainingSlots)
        if (accepted.isNotEmpty()) {
            _uiState.update { it.copy(selectedImageUris = current + accepted) }
        }
        // A successful add clears the previous failure too, not just its message (UP-13).
        _uiState.update { it.copy(isError = false) }
        val dropped = newUris.size - accepted.size
        if (dropped > 0) {
            _uiState.update {
                it.copy(statusMessage = "Up to $MAX_PHOTOS photos per upload; $dropped photo(s) were not added.")
            }
        } else if (accepted.isNotEmpty()) {
            _uiState.update { it.copy(statusMessage = null) }
        }
    }

    fun removeImageAt(index: Int) {
        val currentList = _uiState.value.selectedImageUris
        if (index in currentList.indices) {
            val newList = currentList.toMutableList()
            val removed = newList.removeAt(index)
            _uiState.update { state ->
                var next = state.copy(
                    selectedImageUris = newList,
                    altTexts = state.altTexts.filterKeys { it != removed }
                )
                // Adjust current page if needed
                next = when {
                    next.currentPage >= newList.size && newList.isNotEmpty() ->
                        next.copy(currentPage = newList.size - 1)
                    newList.isEmpty() -> next.copy(currentPage = 0)
                    else -> next
                }
                next
            }
        }
    }

    /**
     * Re-inserts a single image at [index] (clamped), used to undo a removal at its original
     * position. No-ops when the image is already selected or the 6-photo cap is reached, and
     * emits one state update.
     */
    fun insertImageAt(index: Int, uri: Uri) {
        val current = _uiState.value.selectedImageUris
        if (uri in current || current.size >= MAX_PHOTOS) return
        val insertAt = index.coerceIn(0, current.size)
        val newList = current.toMutableList()
        newList.add(insertAt, uri)
        _uiState.update { it.copy(selectedImageUris = newList, currentPage = insertAt) }
    }

    fun shiftLeft(index: Int) {
        val currentList = _uiState.value.selectedImageUris
        if (index > 0 && index < currentList.size) {
            val newList = currentList.toMutableList()
            val temp = newList[index]
            newList[index] = newList[index - 1]
            newList[index - 1] = temp
            _uiState.update { it.copy(selectedImageUris = newList, currentPage = index - 1) }
        }
    }

    fun shiftRight(index: Int) {
        val currentList = _uiState.value.selectedImageUris
        if (index >= 0 && index < currentList.size - 1) {
            val newList = currentList.toMutableList()
            val temp = newList[index]
            newList[index] = newList[index + 1]
            newList[index + 1] = temp
            _uiState.update { it.copy(selectedImageUris = newList, currentPage = index + 1) }
        }
    }

    fun onPageChanged(page: Int) {
        _uiState.update { it.copy(currentPage = page) }
    }

    fun onCaptionChanged(newValue: TextFieldValue) {
        _uiState.update { it.copy(caption = newValue) }
        val context = findHashtagContext(newValue)
        tagSearchJob?.cancel()
        pendingTagQuery = null
        if (context == null || context.query.length < 2) {
            _uiState.update { it.copy(remoteTagSuggestions = emptyList()) }
            return
        }

        searchTagSuggestions(context.query)
    }

    /**
     * Nothing happens for [query] while an identical search is already in flight, so the caption
     * edit and the screen's suggestion effect cannot run the same search twice (UP-13).
     */
    fun searchTagSuggestions(query: String) {
        if (query.length < 2) {
            tagSearchJob?.cancel()
            pendingTagQuery = null
            _uiState.update { it.copy(remoteTagSuggestions = emptyList()) }
            return
        }
        if (query == pendingTagQuery && tagSearchJob?.isActive == true) return

        tagSearchJob?.cancel()
        pendingTagQuery = query
        tagSearchJob = viewModelScope.launch {
            delay(TAG_SEARCH_DEBOUNCE_MS)
            val result = repository.searchTags(query)
            _uiState.update { it.copy(remoteTagSuggestions = result.getOrDefault(emptyList())) }
            refreshCacheSummary()
        }
    }

    fun refreshCacheSummary() {
        viewModelScope.launch {
            _uiState.update { it.copy(cacheSummary = repository.getCacheSummary()) }
        }
    }

    fun insertHash() {
        val current = _uiState.value.caption
        val start = current.selection.min.coerceIn(0, current.text.length)
        val end = current.selection.max.coerceIn(start, current.text.length)
        val newText = current.text.substring(0, start) + "#" + current.text.substring(end)
        onCaptionChanged(
            TextFieldValue(newText, TextRange(start + 1))
        )
    }

    fun onResizeToggled(enabled: Boolean) {
        _uiState.update { it.copy(resizeTo8Mb = enabled) }
    }

    fun setAltText(uri: Uri, altText: String) {
        _uiState.update { state ->
            val altTexts = state.altTexts.toMutableMap()
            if (altText.isBlank()) altTexts.remove(uri) else altTexts[uri] = altText
            state.copy(altTexts = altTexts)
        }
    }

    fun insertTag(tagString: String) {
        val tagName = tagString.trim().removePrefix("#").split(" ").firstOrNull() ?: ""
        if (tagName.isEmpty()) return

        val tagToInsert = "#$tagName "
        val current = _uiState.value.caption
        val text = current.text
        val selection = current.selection
        val start = selection.min.coerceAtLeast(0)
        val end = selection.max.coerceAtLeast(0)

        val newText = text.substring(0, start) + tagToInsert + text.substring(end)
        val newCursorPos = start + tagToInsert.length

        _uiState.update {
            it.copy(caption = TextFieldValue(text = newText, selection = TextRange(newCursorPos)))
        }
    }

    fun insertTagSuggestion(tagString: String) {
        val tagName = tagString.trim().removePrefix("#").split(" ").firstOrNull().orEmpty()
        val context = findHashtagContext(_uiState.value.caption) ?: return
        if (tagName.isEmpty()) return

        val current = _uiState.value.caption
        val replacement = "#$tagName "
        val newText = current.text.replaceRange(context.start, context.end, replacement)
        _uiState.update {
            it.copy(
                caption = TextFieldValue(
                    text = newText,
                    selection = TextRange(context.start + replacement.length)
                )
            )
        }
    }

    fun prefillArtShowTags(theme: String?) {
        val tags = listOfNotNull("#BlueSkyArtShow", theme?.takeIf { it.isNotBlank() })
        val text = _uiState.value.caption.text
        val missing = tags.filter { tag ->
            Regex("(?i)(?<!\\w)${Regex.escape(tag)}(?!\\w)").containsMatchIn(text).not()
        }
        if (missing.isNotEmpty()) {
            val suffix = missing.joinToString(" ") + " "
            _uiState.update {
                it.copy(
                    caption = TextFieldValue(
                        text + if (text.isBlank()) "" else " " + suffix,
                        TextRange(text.length + if (text.isBlank()) suffix.length else suffix.length + 1)
                    )
                )
            }
        }
    }

    /**
     * Enqueues the post and observes it (UP-05). Nothing uploads in [viewModelScope] any more: the
     * work survives the screen and the process, retries with backoff and is cancellable through
     * [cancelUpload].
     */
    fun upload() {
        val uris = _uiState.value.selectedImageUris
        if (uris.isEmpty()) {
            _uiState.update {
                it.copy(statusMessage = "Please select at least one photo", isError = true)
            }
            return
        }
        if (_uiState.value.isUploading) return

        val collectionIds = _uiState.value.selectedCollectionIds.toList()
        val job = UploadJob(
            uploadId = UUID.randomUUID().toString(),
            imageUris = uris.map(Uri::toString),
            caption = captionToPublish(),
            altTexts = uris.mapNotNull { uri ->
                _uiState.value.altTexts[uri]?.takeIf { it.isNotBlank() }?.let { uri.toString() to it }
            }.toMap(),
            resizeTo8Mb = _uiState.value.resizeTo8Mb,
            collectionIds = collectionIds,
            placeId = _uiState.value.selectedPlace?.getIdString()
        )

        _uiState.update {
            it.copy(
                isUploading = true,
                uploadProgress = UploadJobState(UploadJobStatus.Running, 0, uris.size),
                statusMessage = null,
                isError = false
            )
        }
        activeUploadId = job.uploadId

        uploadObservationJob?.cancel()
        uploadObservationJob = viewModelScope.launch {
            try {
                uploadScheduler.enqueue(job)
                // A terminal state is handled once; running states keep reporting per-photo progress.
                uploadScheduler.observe(job.uploadId)
                    .distinctUntilChanged { old, new ->
                        old.status == new.status && new.status != UploadJobStatus.Running
                    }
                    .collect { state ->
                        _uiState.update { it.copy(uploadProgress = state) }
                        when (state.status) {
                            UploadJobStatus.Running ->
                                _uiState.update { it.copy(isUploading = true) }
                            UploadJobStatus.Succeeded -> {
                                _uiState.update { it.copy(isUploading = false) }
                                onUploadSucceeded(uris, collectionIds.size)
                            }
                            UploadJobStatus.Failed -> {
                                _uiState.update { it.copy(isUploading = false) }
                                reportError(state.failureSummary())
                            }
                            UploadJobStatus.Cancelled -> {
                                _uiState.update {
                                    it.copy(
                                        isUploading = false,
                                        statusMessage = "Upload cancelled",
                                        isError = false
                                    )
                                }
                            }
                        }
                    }
                // The observed flow only ends if WorkManager drops the request; never leave the
                // screen spinning on a job nobody is running.
                _uiState.update { it.copy(isUploading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "could not enqueue the upload", e)
                _uiState.update { it.copy(isUploading = false) }
                reportError("Could not start the upload: ${e.describe()}")
            }
        }
    }

    /** Cancels the in-flight upload; the worker stops and already-uploaded media stay checkpointed. */
    fun cancelUpload() {
        val uploadId = activeUploadId ?: return
        uploadScheduler.cancel(uploadId)
    }

    private fun onUploadSucceeded(submitted: List<Uri>, collectionCount: Int) {
        val collectionsMsg = if (collectionCount > 0) {
            " and added to $collectionCount collection(s)"
        } else ""
        _uiState.update { state ->
            state.copy(
                statusMessage = "Successfully uploaded ${submitted.size} photo(s)$collectionsMsg!",
                isError = false,
                // Keep photos picked while the upload was in flight; only the submitted ones are consumed.
                selectedImageUris = state.selectedImageUris.filterNot { it in submitted },
                altTexts = state.altTexts.filterKeys { it !in submitted },
                caption = TextFieldValue(""),
                selectedCollectionIds = emptySet(),
                selectedPlace = null,
                customLocationName = null,
                isExifAutoDetected = false,
                isExifMissing = false,
                placeSearchQuery = "",
                placeSearchResults = emptyList()
            )
        }
        isUserLocationSelectionManual = false
    }

    /** The caption as it will be published, including the manual location line for a place-less post. */
    private fun captionToPublish(): String {
        val caption = _uiState.value.caption.text
        val customLocation = _uiState.value.customLocationName
        if (_uiState.value.selectedPlace?.getIdString() != null || customLocation.isNullOrBlank()) return caption
        if (caption.contains(customLocation)) return caption
        return if (caption.isBlank()) "📍 $customLocation" else "$caption\n📍 $customLocation"
    }

    private fun UploadJobState.failureSummary(): String {
        val detail = errorMessage ?: "the upload did not complete"
        val partial = if (totalCount > 0 && uploadedCount > 0) {
            "Uploaded $uploadedCount/$totalCount photo(s); "
        } else ""
        return "Upload failed: $partial$detail"
    }

    private fun reportError(message: String) {
        _uiState.update { it.copy(statusMessage = message, isError = true) }
    }

    /** A stale-data warning must not overwrite a message the user is currently reading. */
    private fun warnOnce(message: String) {
        if (_uiState.value.statusMessage == null) {
            _uiState.update { it.copy(statusMessage = message, isError = false) }
        }
    }

    private fun Throwable.describe(): String = localizedMessage ?: message ?: toString()

    /**
     * Restores the draft saved by [persistDraft] (UP-09): picking photos via the gallery sends the
     * app to the background, which is exactly where the process can be killed and the whole post
     * used to be lost.
     */
    private fun restoreDraft() {
        savedStateHandle.get<String>(KEY_CAPTION)?.takeIf { it.isNotEmpty() }?.let { text ->
            _uiState.update { it.copy(caption = TextFieldValue(text, TextRange(text.length))) }
        }
        savedStateHandle.get<Boolean>(KEY_RESIZE)?.let { resize ->
            _uiState.update { it.copy(resizeTo8Mb = resize) }
        }
        savedStateHandle.get<ArrayList<String>>(KEY_COLLECTION_IDS)?.let { ids ->
            // A draft saved by an older build could hold more than the server accepts.
            _uiState.update {
                it.copy(selectedCollectionIds = ids.take(MAX_COLLECTIONS_PER_POST).toSet())
            }
        }
        savedStateHandle.get<String>(KEY_CUSTOM_LOCATION)?.let { location ->
            _uiState.update { it.copy(customLocationName = location) }
            isUserLocationSelectionManual = true
        }
        savedStateHandle.get<String>(KEY_PLACE_ID)?.let { placeId ->
            _uiState.update {
                it.copy(
                    selectedPlace = PlaceItem(
                        id = JsonPrimitive(placeId),
                        name = savedStateHandle[KEY_PLACE_NAME],
                        country = savedStateHandle[KEY_PLACE_COUNTRY]
                    )
                )
            }
            isUserLocationSelectionManual = true
        }
        savedStateHandle.get<Map<String, String>>(KEY_ALT_TEXTS)?.forEach { (uri, altText) ->
            runCatching { Uri.parse(uri) }.getOrNull()?.let { parsed ->
                _uiState.update { it.copy(altTexts = it.altTexts + (parsed to altText)) }
            }
        }

        val restoredUris = savedStateHandle.get<ArrayList<String>>(KEY_URIS)
            .orEmpty()
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .take(MAX_PHOTOS)
        if (restoredUris.isEmpty()) return
        // A revoked or deleted URI must be dropped, not crash the metadata or upload path.
        viewModelScope.launch {
            val usable = withContext(Dispatchers.IO) { restoredUris.filter(::canRead) }
            _uiState.update { it.copy(selectedImageUris = usable) }
        }
    }

    private fun canRead(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "restored URI is no longer readable: $uri", e)
        false
    }

    /** Writes the draft through to [savedStateHandle] as it changes; see [restoreDraft]. */
    private fun persistDraft() {
        _uiState.map { it.caption }.distinctUntilChanged()
            .onEach { savedStateHandle[KEY_CAPTION] = it.text }
            .launchIn(viewModelScope)
        _uiState.map { it.selectedImageUris }.distinctUntilChanged()
            .onEach { uris -> savedStateHandle[KEY_URIS] = ArrayList(uris.map(Uri::toString)) }
            .launchIn(viewModelScope)
        _uiState.map { it.resizeTo8Mb }.distinctUntilChanged()
            .onEach { savedStateHandle[KEY_RESIZE] = it }
            .launchIn(viewModelScope)
        _uiState.map { it.selectedCollectionIds }.distinctUntilChanged()
            .onEach { savedStateHandle[KEY_COLLECTION_IDS] = ArrayList(it) }
            .launchIn(viewModelScope)
        _uiState.map { it.customLocationName }.distinctUntilChanged()
            .onEach { savedStateHandle[KEY_CUSTOM_LOCATION] = it }
            .launchIn(viewModelScope)
        _uiState.map { it.selectedPlace }.distinctUntilChanged()
            .onEach { place ->
                savedStateHandle[KEY_PLACE_ID] = place?.getIdString()
                savedStateHandle[KEY_PLACE_NAME] = place?.name
                savedStateHandle[KEY_PLACE_COUNTRY] = place?.country
            }
            .launchIn(viewModelScope)
        _uiState.map { it.altTexts }.distinctUntilChanged()
            .onEach { altTexts ->
                savedStateHandle[KEY_ALT_TEXTS] = HashMap(altTexts.mapKeys { it.key.toString() })
            }
            .launchIn(viewModelScope)
    }

    private companion object {
        const val TAG = "UploadViewModel"
        const val MAX_PHOTOS = 6
        const val PAGE_SIZE = 10
        const val TAG_SEARCH_DEBOUNCE_MS = 300L
        const val PLACE_SEARCH_DEBOUNCE_MS = 300L

        const val KEY_CAPTION = "upload.caption"
        const val KEY_URIS = "upload.uris"
        const val KEY_ALT_TEXTS = "upload.altTexts"
        const val KEY_RESIZE = "upload.resize"
        const val KEY_COLLECTION_IDS = "upload.collectionIds"
        const val KEY_CUSTOM_LOCATION = "upload.customLocation"
        const val KEY_PLACE_ID = "upload.placeId"
        const val KEY_PLACE_NAME = "upload.placeName"
        const val KEY_PLACE_COUNTRY = "upload.placeCountry"
    }
}

data class HashtagContext(val query: String, val start: Int, val end: Int)

fun findHashtagContext(value: TextFieldValue): HashtagContext? {
    if (value.selection.start != value.selection.end) return null
    val cursor = value.selection.max
    if (cursor < 0 || cursor > value.text.length) return null

    var hashStart = cursor - 1
    while (hashStart >= 0 && value.text[hashStart].isHashtagCharacter()) hashStart--
    if (hashStart < 0 || value.text[hashStart] != '#') return null
    if (hashStart > 0 && value.text[hashStart - 1].isHashtagCharacter()) return null

    var end = cursor
    while (end < value.text.length && value.text[end].isHashtagCharacter()) end++
    return HashtagContext(
        query = value.text.substring(hashStart + 1, cursor),
        start = hashStart,
        end = end
    )
}

private fun Char.isHashtagCharacter(): Boolean = isLetterOrDigit() || this == '_' || this == '-'
