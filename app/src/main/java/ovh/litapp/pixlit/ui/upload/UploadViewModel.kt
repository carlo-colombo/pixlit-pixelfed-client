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
import ovh.litapp.pixlit.data.api.CollectionItem
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

    private val _selectedImageUris = MutableStateFlow<List<Uri>>(emptyList())
    val selectedImageUris = _selectedImageUris.asStateFlow()

    private val _captionState = MutableStateFlow(TextFieldValue(""))
    val captionState = _captionState.asStateFlow()

    /** Per-photo alt text (UP-10), keyed by the picked URI. Never the caption. */
    private val _altTexts = MutableStateFlow<Map<Uri, String>>(emptyMap())
    val altTexts = _altTexts.asStateFlow()

    private val _remoteTagSuggestions = MutableStateFlow<List<String>>(emptyList())
    val remoteTagSuggestions = _remoteTagSuggestions.asStateFlow()
    private var tagSearchJob: Job? = null
    private var pendingTagQuery: String? = null

    private val _cacheSummary = MutableStateFlow<List<CacheSummaryItem>>(emptyList())
    val cacheSummary = _cacheSummary.asStateFlow()

    private val _resizeTo8Mb = MutableStateFlow(false)
    val resizeTo8Mb = _resizeTo8Mb.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading = _isUploading.asStateFlow()

    /** Per-photo progress of the work-managed upload (UP-05); `null` before the first enqueue. */
    private val _uploadProgress = MutableStateFlow<UploadJobState?>(null)
    val uploadProgress = _uploadProgress.asStateFlow()
    private var activeUploadId: String? = null
    private var uploadObservationJob: Job? = null

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    private val _isError = MutableStateFlow(false)
    val isError = _isError.asStateFlow()

    private val _topTags = MutableStateFlow<List<TagCount>>(emptyList())
    val topTags = _topTags.asStateFlow()

    private val _recentStatuses = MutableStateFlow<List<StatusItem>>(emptyList())
    val recentStatuses = _recentStatuses.asStateFlow()

    private val _visibleRecentPostCount = MutableStateFlow(PAGE_SIZE)
    val visibleRecentPostCount = _visibleRecentPostCount.asStateFlow()

    private val _isLoadingMorePosts = MutableStateFlow(false)
    val isLoadingMorePosts = _isLoadingMorePosts.asStateFlow()

    private val _isLoadingTags = MutableStateFlow(false)
    val isLoadingTags = _isLoadingTags.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    /**
     * Starts empty: a genuinely empty collection list must reach the UI as an empty state instead
     * of the fabricated placeholders the facade used to substitute (DATA-05 / UP-13).
     */
    private val _userCollections = MutableStateFlow<List<CollectionItem>>(emptyList())
    val userCollections = _userCollections.asStateFlow()

    private val _selectedCollectionIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedCollectionIds = _selectedCollectionIds.asStateFlow()

    private val _isLoadingCollections = MutableStateFlow(false)
    val isLoadingCollections = _isLoadingCollections.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage = _currentPage.asStateFlow()

    private val _originalMetadata = MutableStateFlow<ImageMetadata?>(null)
    val originalMetadata = _originalMetadata.asStateFlow()

    private val _resizedMetadata = MutableStateFlow<ImageMetadata?>(null)
    val resizedMetadata = _resizedMetadata.asStateFlow()

    private val _isCalculatingResized = MutableStateFlow(false)
    val isCalculatingResized = _isCalculatingResized.asStateFlow()

    private val _selectedPlace = MutableStateFlow<PlaceItem?>(null)
    val selectedPlace = _selectedPlace.asStateFlow()

    private val _customLocationName = MutableStateFlow<String?>(null)
    val customLocationName = _customLocationName.asStateFlow()

    private val _isExifAutoDetected = MutableStateFlow(false)
    val isExifAutoDetected = _isExifAutoDetected.asStateFlow()

    private val _isExifMissing = MutableStateFlow(false)
    val isExifMissing = _isExifMissing.asStateFlow()

    private val _placeSearchQuery = MutableStateFlow("")
    val placeSearchQuery = _placeSearchQuery.asStateFlow()

    private val _placeSearchResults = MutableStateFlow<List<PlaceItem>>(emptyList())
    val placeSearchResults = _placeSearchResults.asStateFlow()

    /** The place-search box spinner; EXIF geocoding has its own flag ([isDetectingExifLocation]). */
    private val _isSearchingPlaces = MutableStateFlow(false)
    val isSearchingPlaces = _isSearchingPlaces.asStateFlow()

    private val _isDetectingExifLocation = MutableStateFlow(false)
    val isDetectingExifLocation = _isDetectingExifLocation.asStateFlow()

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
            if (_topTags.value.isEmpty()) {
                _topTags.value = staticTagCounts
            }
        }

        // Load the real account history so the recent-posts section is useful on first render.
        fetchTags(forceRefresh = true)
        fetchCollections()
        refreshCacheSummary()

        // Sync metadata when current page or URIs or resize setting changes.
        // collectLatest cancels the superseded page instead of letting it write stale metadata.
        viewModelScope.launch {
            combine(
                _currentPage,
                _selectedImageUris,
                _resizeTo8Mb
            ) { page, uris, resize ->
                Triple(page, uris, resize)
            }.collectLatest { (page, uris, resize) ->
                val uri = uris.getOrNull(page)
                if (uri != null) {
                    updateMetadata(uri, resize)
                } else {
                    clearMetadata()
                }
            }
        }

        _selectedImageUris.onEach { uris ->
            if (uris.isEmpty()) {
                _isExifMissing.value = false
                if (!isUserLocationSelectionManual) {
                    _selectedPlace.value = null
                    _customLocationName.value = null
                    _isExifAutoDetected.value = false
                }
            } else if (!isUserLocationSelectionManual && _selectedPlace.value == null && _customLocationName.value == null) {
                detectExifLocation(uris)
            }
        }.launchIn(viewModelScope)

        persistDraft()
    }

    fun detectExifLocation(uris: List<Uri>) {
        exifDetectionJob?.cancel()
        exifDetectionJob = viewModelScope.launch {
            _isDetectingExifLocation.value = true
            try {
                var foundExif = false
                for (uri in uris) {
                    try {
                        val coords = withContext(Dispatchers.IO) {
                            ImageUtils.extractExifLocation(context, uri)
                        } ?: continue
                        foundExif = true
                        _isExifMissing.value = false
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
                                _selectedPlace.value != null ||
                                _customLocationName.value != null
                            ) {
                                return@withContext
                            }
                            _customLocationName.value = locationName
                            _selectedPlace.value = null
                            _isExifAutoDetected.value = true
                        }
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "EXIF location detection failed", e)
                    }
                }
                if (!foundExif && isActive) {
                    _isExifMissing.value = true
                }
            } finally {
                _isDetectingExifLocation.value = false
            }
        }
    }

    /**
     * [_isSearchingPlaces] flips to `true` before the debounce delay so the composable's
     * "Use … as location name" fallback stays hidden while the user is typing.
     */
    fun onPlaceSearchQueryChanged(query: String) {
        _placeSearchQuery.value = query
        placeSearchJob?.cancel()
        if (query.isBlank()) {
            _placeSearchResults.value = emptyList()
            _isSearchingPlaces.value = false
            return
        }
        _isSearchingPlaces.value = true
        placeSearchJob = viewModelScope.launch {
            delay(PLACE_SEARCH_DEBOUNCE_MS)
            // A failed search shows no results but is logged; it is not an empty success (DATA-05).
            when (val fetch = repository.searchPlaces(query = query.trim())) {
                is Fetch.Fresh -> _placeSearchResults.value = fetch.value
                is Fetch.Cached -> _placeSearchResults.value = fetch.value
                is Fetch.Failed -> {
                    Log.e(TAG, "place search failed", fetch.cause)
                    _placeSearchResults.value = emptyList()
                }
            }
            _isSearchingPlaces.value = false
        }
    }

    fun selectPlace(place: PlaceItem) {
        exifDetectionJob?.cancel()
        _selectedPlace.value = place
        _customLocationName.value = null
        _isExifAutoDetected.value = false
        _placeSearchQuery.value = ""
        _placeSearchResults.value = emptyList()
        isUserLocationSelectionManual = true
    }

    fun setCustomLocation(name: String) {
        if (name.isBlank()) {
            clearLocation()
            return
        }
        exifDetectionJob?.cancel()
        _selectedPlace.value = null
        _customLocationName.value = name.trim()
        _isExifAutoDetected.value = false
        _placeSearchQuery.value = ""
        _placeSearchResults.value = emptyList()
        isUserLocationSelectionManual = true
    }

    fun clearLocation() {
        exifDetectionJob?.cancel()
        _selectedPlace.value = null
        _customLocationName.value = null
        _isExifAutoDetected.value = false
        _placeSearchQuery.value = ""
        _placeSearchResults.value = emptyList()
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
            _originalMetadata.value = meta

            if (resize && meta != null && meta.sizeBytes > ImageUtils.MAX_BYTES_8MB) {
                _isCalculatingResized.value = true
                val prepared = repository.prepareImage(uri, resize = true).getOrNull()
                if (metadataRequestUri != uri) return
                _resizedMetadata.value = prepared?.let { ImageUtils.getFileMetadata(it.file) }
            } else {
                _resizedMetadata.value = null
            }
        } finally {
            if (metadataRequestUri == uri) {
                _isCalculatingResized.value = false
            }
        }
    }

    private fun clearMetadata() {
        metadataRequestUri = null
        _originalMetadata.value = null
        _resizedMetadata.value = null
        _isCalculatingResized.value = false
    }

    fun fetchCollections() {
        viewModelScope.launch {
            _isLoadingCollections.value = true
            try {
                when (val fetch = repository.getUserCollections()) {
                    is Fetch.Fresh -> _userCollections.value = fetch.value
                    is Fetch.Cached -> {
                        _userCollections.value = fetch.value
                        warnOnce("Showing cached collections — the refresh failed")
                    }
                    is Fetch.Failed -> {
                        Log.e(TAG, "fetchCollections failure", fetch.cause)
                        reportError("Could not load your collections: ${fetch.cause.describe()}")
                    }
                }
            } finally {
                _isLoadingCollections.value = false
            }
        }
    }

    fun toggleCollectionSelection(collectionId: String) {
        val current = _selectedCollectionIds.value.toMutableSet()
        if (current.contains(collectionId)) {
            current.remove(collectionId)
        } else {
            current.add(collectionId)
        }
        _selectedCollectionIds.value = current
    }

    fun createAndSelectCollection(title: String, description: String? = null) {
        if (title.isBlank()) return
        viewModelScope.launch {
            // A failed creation is reported, never shown as a created collection (DATA-05).
            val created = repository.createCollection(title.trim(), description?.trim()).valueOrNull()
            if (created == null) {
                reportError("Could not create the collection \"${title.trim()}\"")
                return@launch
            }
            val id = created.getIdString()
            val currentCollections = _userCollections.value.toMutableList()
            if (id != null && currentCollections.none { it.getIdString() == id }) {
                currentCollections.add(0, created)
                _userCollections.value = currentCollections
                toggleCollectionSelection(id)
            }
        }
    }

    fun refreshRecentPosts() {
        viewModelScope.launch {
            _isRefreshing.value = true
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
                _isRefreshing.value = false
            }
        }
    }

    fun fetchTags(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isLoadingTags.value = true
            try {
                when (val fetch = repository.getUserTopTagsAndPosts(forceRefresh = forceRefresh)) {
                    is Fetch.Fresh -> applyTagsAndPosts(fetch.value)
                    is Fetch.Cached -> {
                        applyTagsAndPosts(fetch.value)
                        warnOnce("Showing cached posts — the refresh failed")
                    }
                    is Fetch.Failed -> {
                        Log.e(TAG, "fetchTags failure", fetch.cause)
                        _topTags.value = repository.getDefaultStaticTagCounts()
                        reportError("Could not load your tags and posts: ${fetch.cause.describe()}")
                    }
                }
            } finally {
                _isLoadingTags.value = false
            }
        }
    }

    private suspend fun applyTagsAndPosts(data: PixelfedRepository.TagsAndPosts) {
        _topTags.value = if (data.topTags.isNotEmpty()) {
            data.topTags
        } else {
            repository.getDefaultStaticTagCounts()
        }
        if (data.statuses.isNotEmpty()) {
            _recentStatuses.value = data.statuses
            _visibleRecentPostCount.value = PAGE_SIZE
        }
    }

    fun loadMoreRecentPosts() {
        if (_isLoadingMorePosts.value) return
        if (_visibleRecentPostCount.value < _recentStatuses.value.size) {
            _visibleRecentPostCount.value += PAGE_SIZE
            return
        }

        val lastId = _recentStatuses.value.lastOrNull()?.getIdString() ?: return
        viewModelScope.launch {
            _isLoadingMorePosts.value = true
            repository.getMoreUserStatuses(lastId).onSuccess { moreStatuses ->
                val existingIds = _recentStatuses.value.mapNotNull { it.getIdString() }.toSet()
                val newStatuses = moreStatuses.filter { it.getIdString() !in existingIds }
                _recentStatuses.value += newStatuses
                _visibleRecentPostCount.value += PAGE_SIZE
            }
            _isLoadingMorePosts.value = false
        }
    }

    fun addImages(uris: List<Uri>) {
        val current = _selectedImageUris.value
        // A pick can repeat a URI (e.g. the same photo tapped twice) or re-send one already selected.
        val newUris = uris.distinct().filterNot { it in current }
        val remainingSlots = (MAX_PHOTOS - current.size).coerceAtLeast(0)
        val accepted = newUris.take(remainingSlots)
        if (accepted.isNotEmpty()) {
            _selectedImageUris.value = current + accepted
        }
        // A successful add clears the previous failure too, not just its message (UP-13).
        _isError.value = false
        val dropped = newUris.size - accepted.size
        if (dropped > 0) {
            _statusMessage.value =
                "Up to $MAX_PHOTOS photos per upload; $dropped photo(s) were not added."
        } else if (accepted.isNotEmpty()) {
            _statusMessage.value = null
        }
    }

    fun removeImageAt(index: Int) {
        val currentList = _selectedImageUris.value
        if (index in currentList.indices) {
            val newList = currentList.toMutableList()
            val removed = newList.removeAt(index)
            _selectedImageUris.value = newList
            _altTexts.value = _altTexts.value.filterKeys { it != removed }

            // Adjust current page if needed
            if (_currentPage.value >= newList.size && newList.isNotEmpty()) {
                _currentPage.value = newList.size - 1
            } else if (newList.isEmpty()) {
                _currentPage.value = 0
            }
        }
    }

    /**
     * Re-inserts a single image at [index] (clamped), used to undo a removal at its original
     * position. No-ops when the image is already selected or the 6-photo cap is reached, and
     * emits one state update.
     */
    fun insertImageAt(index: Int, uri: Uri) {
        val current = _selectedImageUris.value
        if (uri in current || current.size >= MAX_PHOTOS) return
        val insertAt = index.coerceIn(0, current.size)
        val newList = current.toMutableList()
        newList.add(insertAt, uri)
        _selectedImageUris.value = newList
        _currentPage.value = insertAt
    }

    fun shiftLeft(index: Int) {
        val currentList = _selectedImageUris.value
        if (index > 0 && index < currentList.size) {
            val newList = currentList.toMutableList()
            val temp = newList[index]
            newList[index] = newList[index - 1]
            newList[index - 1] = temp
            _selectedImageUris.value = newList
            _currentPage.value = index - 1
        }
    }

    fun shiftRight(index: Int) {
        val currentList = _selectedImageUris.value
        if (index >= 0 && index < currentList.size - 1) {
            val newList = currentList.toMutableList()
            val temp = newList[index]
            newList[index] = newList[index + 1]
            newList[index + 1] = temp
            _selectedImageUris.value = newList
            _currentPage.value = index + 1
        }
    }

    fun onPageChanged(page: Int) {
        _currentPage.value = page
    }

    fun onCaptionChanged(newValue: TextFieldValue) {
        _captionState.value = newValue
        val context = findHashtagContext(newValue)
        tagSearchJob?.cancel()
        pendingTagQuery = null
        if (context == null || context.query.length < 2) {
            _remoteTagSuggestions.value = emptyList()
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
            _remoteTagSuggestions.value = emptyList()
            return
        }
        if (query == pendingTagQuery && tagSearchJob?.isActive == true) return

        tagSearchJob?.cancel()
        pendingTagQuery = query
        tagSearchJob = viewModelScope.launch {
            delay(TAG_SEARCH_DEBOUNCE_MS)
            val result = repository.searchTags(query)
            _remoteTagSuggestions.value = result.getOrDefault(emptyList())
            refreshCacheSummary()
        }
    }

    fun refreshCacheSummary() {
        viewModelScope.launch {
            _cacheSummary.value = repository.getCacheSummary()
        }
    }

    fun insertHash() {
        val current = _captionState.value
        val start = current.selection.min.coerceIn(0, current.text.length)
        val end = current.selection.max.coerceIn(start, current.text.length)
        val newText = current.text.substring(0, start) + "#" + current.text.substring(end)
        onCaptionChanged(
            TextFieldValue(newText, TextRange(start + 1))
        )
    }

    fun onResizeToggled(enabled: Boolean) {
        _resizeTo8Mb.value = enabled
    }

    fun setAltText(uri: Uri, altText: String) {
        _altTexts.value = _altTexts.value.toMutableMap().apply {
            if (altText.isBlank()) remove(uri) else put(uri, altText)
        }
    }

    fun insertTag(tagString: String) {
        val tagName = tagString.trim().removePrefix("#").split(" ").firstOrNull() ?: ""
        if (tagName.isEmpty()) return

        val tagToInsert = "#$tagName "
        val current = _captionState.value
        val text = current.text
        val selection = current.selection
        val start = selection.min.coerceAtLeast(0)
        val end = selection.max.coerceAtLeast(0)

        val newText = text.substring(0, start) + tagToInsert + text.substring(end)
        val newCursorPos = start + tagToInsert.length

        _captionState.value = TextFieldValue(
            text = newText,
            selection = TextRange(newCursorPos)
        )
    }

    fun insertTagSuggestion(tagString: String) {
        val tagName = tagString.trim().removePrefix("#").split(" ").firstOrNull().orEmpty()
        val context = findHashtagContext(_captionState.value) ?: return
        if (tagName.isEmpty()) return

        val current = _captionState.value
        val replacement = "#$tagName "
        val newText = current.text.replaceRange(context.start, context.end, replacement)
        _captionState.value = TextFieldValue(
            text = newText,
            selection = TextRange(context.start + replacement.length)
        )
    }

    fun prefillArtShowTags(theme: String?) {
        val tags = listOfNotNull("#BlueSkyArtShow", theme?.takeIf { it.isNotBlank() })
        val text = _captionState.value.text
        val missing = tags.filter { tag ->
            Regex("(?i)(?<!\\w)${Regex.escape(tag)}(?!\\w)").containsMatchIn(text).not()
        }
        if (missing.isNotEmpty()) {
            val suffix = missing.joinToString(" ") + " "
            _captionState.value = TextFieldValue(text + if (text.isBlank()) "" else " " + suffix, TextRange(text.length + if (text.isBlank()) suffix.length else suffix.length + 1))
        }
    }

    /**
     * Enqueues the post and observes it (UP-05). Nothing uploads in [viewModelScope] any more: the
     * work survives the screen and the process, retries with backoff and is cancellable through
     * [cancelUpload].
     */
    fun upload() {
        val uris = _selectedImageUris.value
        if (uris.isEmpty()) {
            _statusMessage.value = "Please select at least one photo"
            _isError.value = true
            return
        }
        if (_isUploading.value) return

        val collectionIds = _selectedCollectionIds.value.toList()
        val job = UploadJob(
            uploadId = UUID.randomUUID().toString(),
            imageUris = uris.map(Uri::toString),
            caption = captionToPublish(),
            altTexts = uris.mapNotNull { uri ->
                _altTexts.value[uri]?.takeIf { it.isNotBlank() }?.let { uri.toString() to it }
            }.toMap(),
            resizeTo8Mb = _resizeTo8Mb.value,
            collectionIds = collectionIds,
            placeId = _selectedPlace.value?.getIdString()
        )

        _isUploading.value = true
        _uploadProgress.value = UploadJobState(UploadJobStatus.Running, 0, uris.size)
        _statusMessage.value = null
        _isError.value = false
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
                        _uploadProgress.value = state
                        when (state.status) {
                            UploadJobStatus.Running -> _isUploading.value = true
                            UploadJobStatus.Succeeded -> {
                                _isUploading.value = false
                                onUploadSucceeded(uris, collectionIds.size)
                            }
                            UploadJobStatus.Failed -> {
                                _isUploading.value = false
                                reportError(state.failureSummary())
                            }
                            UploadJobStatus.Cancelled -> {
                                _isUploading.value = false
                                _statusMessage.value = "Upload cancelled"
                                _isError.value = false
                            }
                        }
                    }
                // The observed flow only ends if WorkManager drops the request; never leave the
                // screen spinning on a job nobody is running.
                _isUploading.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "could not enqueue the upload", e)
                _isUploading.value = false
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
        _statusMessage.value = "Successfully uploaded ${submitted.size} photo(s)$collectionsMsg!"
        _isError.value = false
        // Keep photos picked while the upload was in flight; only the submitted ones are consumed.
        _selectedImageUris.value = _selectedImageUris.value.filterNot { it in submitted }
        _altTexts.value = _altTexts.value.filterKeys { it !in submitted }
        _captionState.value = TextFieldValue("")
        _selectedCollectionIds.value = emptySet()
        _selectedPlace.value = null
        _customLocationName.value = null
        _isExifAutoDetected.value = false
        _isExifMissing.value = false
        isUserLocationSelectionManual = false
        _placeSearchQuery.value = ""
        _placeSearchResults.value = emptyList()
    }

    /** The caption as it will be published, including the manual location line for a place-less post. */
    private fun captionToPublish(): String {
        val caption = _captionState.value.text
        val customLocation = _customLocationName.value
        if (_selectedPlace.value?.getIdString() != null || customLocation.isNullOrBlank()) return caption
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
        _statusMessage.value = message
        _isError.value = true
    }

    /** A stale-data warning must not overwrite a message the user is currently reading. */
    private fun warnOnce(message: String) {
        if (_statusMessage.value == null) {
            _statusMessage.value = message
            _isError.value = false
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
            _captionState.value = TextFieldValue(text, TextRange(text.length))
        }
        savedStateHandle.get<Boolean>(KEY_RESIZE)?.let { _resizeTo8Mb.value = it }
        savedStateHandle.get<ArrayList<String>>(KEY_COLLECTION_IDS)?.let {
            _selectedCollectionIds.value = it.toSet()
        }
        savedStateHandle.get<String>(KEY_CUSTOM_LOCATION)?.let {
            _customLocationName.value = it
            isUserLocationSelectionManual = true
        }
        savedStateHandle.get<String>(KEY_PLACE_ID)?.let { placeId ->
            _selectedPlace.value = PlaceItem(
                id = JsonPrimitive(placeId),
                name = savedStateHandle[KEY_PLACE_NAME],
                country = savedStateHandle[KEY_PLACE_COUNTRY]
            )
            isUserLocationSelectionManual = true
        }
        savedStateHandle.get<Map<String, String>>(KEY_ALT_TEXTS)?.forEach { (uri, altText) ->
            runCatching { Uri.parse(uri) }.getOrNull()?.let { _altTexts.value += it to altText }
        }

        val restoredUris = savedStateHandle.get<ArrayList<String>>(KEY_URIS)
            .orEmpty()
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .take(MAX_PHOTOS)
        if (restoredUris.isEmpty()) return
        // A revoked or deleted URI must be dropped, not crash the metadata or upload path.
        viewModelScope.launch {
            val usable = withContext(Dispatchers.IO) { restoredUris.filter(::canRead) }
            _selectedImageUris.value = usable
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
        _captionState.onEach { savedStateHandle[KEY_CAPTION] = it.text }.launchIn(viewModelScope)
        _selectedImageUris.onEach { uris ->
            savedStateHandle[KEY_URIS] = ArrayList(uris.map(Uri::toString))
        }.launchIn(viewModelScope)
        _resizeTo8Mb.onEach { savedStateHandle[KEY_RESIZE] = it }.launchIn(viewModelScope)
        _selectedCollectionIds.onEach {
            savedStateHandle[KEY_COLLECTION_IDS] = ArrayList(it)
        }.launchIn(viewModelScope)
        _customLocationName.onEach { savedStateHandle[KEY_CUSTOM_LOCATION] = it }.launchIn(viewModelScope)
        _selectedPlace.onEach { place ->
            savedStateHandle[KEY_PLACE_ID] = place?.getIdString()
            savedStateHandle[KEY_PLACE_NAME] = place?.name
            savedStateHandle[KEY_PLACE_COUNTRY] = place?.country
        }.launchIn(viewModelScope)
        _altTexts.onEach { altTexts ->
            savedStateHandle[KEY_ALT_TEXTS] = HashMap(altTexts.mapKeys { it.key.toString() })
        }.launchIn(viewModelScope)
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
