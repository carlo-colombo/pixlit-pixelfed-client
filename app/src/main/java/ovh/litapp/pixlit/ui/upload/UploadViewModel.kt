package ovh.litapp.pixlit.ui.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.data.api.PlaceItem
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.utils.ImageMetadata
import ovh.litapp.pixlit.utils.ImageUtils
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PixelfedRepository
) : ViewModel() {

    private val _selectedImageUris = MutableStateFlow<List<Uri>>(emptyList())
    val selectedImageUris = _selectedImageUris.asStateFlow()

    private val _captionState = MutableStateFlow(TextFieldValue(""))
    val captionState = _captionState.asStateFlow()

    private val _resizeTo8Mb = MutableStateFlow(false)
    val resizeTo8Mb = _resizeTo8Mb.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading = _isUploading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    private val _isError = MutableStateFlow(false)
    val isError = _isError.asStateFlow()

    private val _topTags = MutableStateFlow(repository.getDefaultStaticTagCounts())
    val topTags = _topTags.asStateFlow()

    private val _recentStatuses = MutableStateFlow<List<StatusItem>>(emptyList())
    val recentStatuses = _recentStatuses.asStateFlow()

    private val _visibleRecentPostCount = MutableStateFlow(10)
    val visibleRecentPostCount = _visibleRecentPostCount.asStateFlow()

    private val _isLoadingMorePosts = MutableStateFlow(false)
    val isLoadingMorePosts = _isLoadingMorePosts.asStateFlow()

    private val _isLoadingTags = MutableStateFlow(false)
    val isLoadingTags = _isLoadingTags.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _userCollections = MutableStateFlow<List<CollectionItem>>(repository.getDefaultStaticCollections())
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

    private val _placeSearchQuery = MutableStateFlow("")
    val placeSearchQuery = _placeSearchQuery.asStateFlow()

    private val _placeSearchResults = MutableStateFlow<List<PlaceItem>>(emptyList())
    val placeSearchResults = _placeSearchResults.asStateFlow()

    private val _isSearchingPlaces = MutableStateFlow(false)
    val isSearchingPlaces = _isSearchingPlaces.asStateFlow()

    private var placeSearchJob: kotlinx.coroutines.Job? = null
    private var isUserLocationSelectionManual = false

    init {
        // Load the real account history so the recent-posts section is useful on first render.
        fetchTags(forceRefresh = true)
        fetchCollections()

        // Sync metadata when current page or URIs or resize setting changes
        combine(
            _currentPage,
            _selectedImageUris,
            _resizeTo8Mb
        ) { page, uris, resize ->
            Triple(page, uris, resize)
        }.onEach { (page, uris, resize) ->
            val uri = uris.getOrNull(page)
            if (uri != null) {
                updateMetadata(uri, resize)
            } else {
                _originalMetadata.value = null
                _resizedMetadata.value = null
            }
        }.launchIn(viewModelScope)

        _selectedImageUris.onEach { uris ->
            if (uris.isEmpty()) {
                if (!isUserLocationSelectionManual) {
                    _selectedPlace.value = null
                    _customLocationName.value = null
                    _isExifAutoDetected.value = false
                }
            } else if (!isUserLocationSelectionManual && _selectedPlace.value == null && _customLocationName.value == null) {
                detectExifLocation(uris)
            }
        }.launchIn(viewModelScope)
    }

    fun detectExifLocation(uris: List<Uri>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        val coords = ImageUtils.extractExifLocation(context, uri)
                        if (coords != null) {
                            val (lat, lng) = coords
                            _isSearchingPlaces.value = true
                            val result = repository.searchPlaces(lat = lat, lng = lng)
                            _isSearchingPlaces.value = false
                            result.fold(
                                onSuccess = { places ->
                                    if (places.isNotEmpty()) {
                                        _selectedPlace.value = places.first()
                                        _customLocationName.value = null
                                        _isExifAutoDetected.value = true
                                    } else {
                                        val formatted = String.format(java.util.Locale.US, "%.4f, %.4f", lat, lng)
                                        _customLocationName.value = formatted
                                        _selectedPlace.value = null
                                        _isExifAutoDetected.value = true
                                    }
                                },
                                onFailure = {
                                    val formatted = String.format(java.util.Locale.US, "%.4f, %.4f", lat, lng)
                                    _customLocationName.value = formatted
                                    _selectedPlace.value = null
                                    _isExifAutoDetected.value = true
                                }
                            )
                            break
                        }
                    } catch (e: Exception) {
                        Log.e("UploadViewModel", "EXIF location detection failed", e)
                    }
                }
            }
        }
    }

    fun onPlaceSearchQueryChanged(query: String) {
        _placeSearchQuery.value = query
        placeSearchJob?.cancel()
        if (query.isBlank()) {
            _placeSearchResults.value = emptyList()
            _isSearchingPlaces.value = false
            return
        }
        placeSearchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            _isSearchingPlaces.value = true
            val result = repository.searchPlaces(query = query.trim())
            result.fold(
                onSuccess = { places ->
                    _placeSearchResults.value = places
                },
                onFailure = {
                    _placeSearchResults.value = emptyList()
                }
            )
            _isSearchingPlaces.value = false
        }
    }

    fun selectPlace(place: PlaceItem) {
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
        _selectedPlace.value = null
        _customLocationName.value = name.trim()
        _isExifAutoDetected.value = false
        _placeSearchQuery.value = ""
        _placeSearchResults.value = emptyList()
        isUserLocationSelectionManual = true
    }

    fun clearLocation() {
        _selectedPlace.value = null
        _customLocationName.value = null
        _isExifAutoDetected.value = false
        _placeSearchQuery.value = ""
        _placeSearchResults.value = emptyList()
        isUserLocationSelectionManual = true
    }

    private fun updateMetadata(uri: Uri, resize: Boolean) {
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) {
                ImageUtils.getImageMetadata(context, uri)
            }
            _originalMetadata.value = meta

            if (resize && meta != null && meta.sizeBytes > ImageUtils.MAX_BYTES_8MB) {
                _isCalculatingResized.value = true
                val resizedFile = withContext(Dispatchers.IO) {
                    ImageUtils.resizeImageDownToMaxBytes(context, uri, ImageUtils.MAX_BYTES_8MB)
                }
                _resizedMetadata.value = resizedFile?.let { ImageUtils.getFileMetadata(it) }
                _isCalculatingResized.value = false
            } else {
                _resizedMetadata.value = null
            }
        }
    }

    fun fetchCollections() {
        viewModelScope.launch {
            _isLoadingCollections.value = true
            val result = repository.getUserCollections()
            result.fold(
                onSuccess = { collections ->
                    _userCollections.value = if (collections.isNotEmpty()) collections else repository.getDefaultStaticCollections()
                },
                onFailure = { ex ->
                    Log.e("UploadViewModel", "fetchCollections failure", ex)
                    _userCollections.value = repository.getDefaultStaticCollections()
                }
            )
            _isLoadingCollections.value = false
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
            val result = repository.createCollection(title.trim(), description?.trim())
            result.fold(
                onSuccess = { newCollection ->
                    val id = newCollection.getIdString()
                    val currentCollections = _userCollections.value.toMutableList()
                    if (id != null && currentCollections.none { it.getIdString() == id }) {
                        currentCollections.add(0, newCollection)
                        _userCollections.value = currentCollections
                        toggleCollectionSelection(id)
                    }
                },
                onFailure = { ex ->
                    Log.e("UploadViewModel", "createCollection failure", ex)
                }
            )
        }
    }

    fun refreshRecentPosts() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = repository.getUserTopTagsAndPosts(forceRefresh = true)
            result.fold(
                onSuccess = { data ->
                    if (data.topTags.isNotEmpty()) {
                        _topTags.value = data.topTags
                    }
                    if (data.statuses.isNotEmpty()) {
                        _recentStatuses.value = data.statuses
                        _visibleRecentPostCount.value = 10
                    }
                },
                onFailure = { ex ->
                    Log.e("UploadViewModel", "refreshRecentPosts failure", ex)
                }
            )
            _isRefreshing.value = false
        }
    }

    fun fetchTags(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isLoadingTags.value = true
            val result = repository.getUserTopTagsAndPosts(forceRefresh = forceRefresh)
            result.fold(
                onSuccess = { data ->
                    _topTags.value = if (data.topTags.isNotEmpty()) data.topTags else repository.getDefaultStaticTagCounts()
                    if (data.statuses.isNotEmpty()) {
                        _recentStatuses.value = data.statuses
                        _visibleRecentPostCount.value = 10
                    }
                },
                onFailure = { ex ->
                    Log.e("UploadViewModel", "fetchTags failure", ex)
                    if (_topTags.value.isEmpty()) {
                        _topTags.value = repository.getDefaultStaticTagCounts()
                    }
                }
            )
            _isLoadingTags.value = false
        }
    }

    fun loadMoreRecentPosts() {
        if (_isLoadingMorePosts.value) return
        if (_visibleRecentPostCount.value < _recentStatuses.value.size) {
            _visibleRecentPostCount.value += 10
            return
        }

        val lastId = _recentStatuses.value.lastOrNull()?.getIdString() ?: return
        viewModelScope.launch {
            _isLoadingMorePosts.value = true
            repository.getMoreUserStatuses(lastId).onSuccess { moreStatuses ->
                val existingIds = _recentStatuses.value.mapNotNull { it.getIdString() }.toSet()
                val newStatuses = moreStatuses.filter { it.getIdString() !in existingIds }
                _recentStatuses.value += newStatuses
                _visibleRecentPostCount.value += 10
            }
            _isLoadingMorePosts.value = false
        }
    }

    fun addImages(uris: List<Uri>) {
        val combined = (_selectedImageUris.value + uris).take(6)
        _selectedImageUris.value = combined
        _statusMessage.value = null
    }

    fun removeImageAt(index: Int) {
        val currentList = _selectedImageUris.value
        if (index in currentList.indices) {
            val newList = currentList.toMutableList()
            newList.removeAt(index)
            _selectedImageUris.value = newList
            
            // Adjust current page if needed
            if (_currentPage.value >= newList.size && newList.isNotEmpty()) {
                _currentPage.value = newList.size - 1
            } else if (newList.isEmpty()) {
                _currentPage.value = 0
            }
        }
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
    }

    fun onResizeToggled(enabled: Boolean) {
        _resizeTo8Mb.value = enabled
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

    fun upload() {
        val uris = _selectedImageUris.value
        if (uris.isEmpty()) {
            _statusMessage.value = "Please select at least one photo"
            _isError.value = true
            return
        }

        _isUploading.value = true
        _statusMessage.value = null
        _isError.value = false

        viewModelScope.launch {
            val collectionsToAssign = _selectedCollectionIds.value.toList()
            val placeIdToPass = _selectedPlace.value?.getIdString()
            var captionToUse = _captionState.value.text
            if (placeIdToPass == null && !_customLocationName.value.isNullOrBlank()) {
                val locText = _customLocationName.value!!
                if (!captionToUse.contains(locText)) {
                    captionToUse = if (captionToUse.isBlank()) "📍 $locText" else "$captionToUse\n📍 $locText"
                }
            }

            val result = repository.uploadPhotosAndCreateStatus(
                imageUris = uris,
                caption = captionToUse,
                resizeTo8Mb = _resizeTo8Mb.value,
                collectionIds = collectionsToAssign,
                placeId = placeIdToPass
            )
            _isUploading.value = false
            result.fold(
                onSuccess = {
                    val collectionsMsg = if (collectionsToAssign.isNotEmpty()) {
                        " and added to ${collectionsToAssign.size} collection(s)"
                    } else ""
                    _statusMessage.value = "Successfully uploaded ${uris.size} photo(s)$collectionsMsg!"
                    _isError.value = false
                    _selectedImageUris.value = emptyList()
                    _captionState.value = TextFieldValue("")
                    _selectedCollectionIds.value = emptySet()
                    _selectedPlace.value = null
                    _customLocationName.value = null
                    _isExifAutoDetected.value = false
                    isUserLocationSelectionManual = false
                    _placeSearchQuery.value = ""
                    _placeSearchResults.value = emptyList()
                },
                onFailure = { ex ->
                    val msg = ex.localizedMessage ?: ex.message ?: ex.toString()
                    _statusMessage.value = "Upload failed: $msg"
                    _isError.value = true
                    Log.e("UploadViewModel", "Upload failed: $msg", ex)
                }
            )
        }
    }
}
