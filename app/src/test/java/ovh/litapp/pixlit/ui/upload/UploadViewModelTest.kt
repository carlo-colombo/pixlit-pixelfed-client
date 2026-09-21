package ovh.litapp.pixlit.ui.upload

import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import ovh.litapp.pixlit.data.api.CollectionItem
import ovh.litapp.pixlit.data.api.PlaceItem
import ovh.litapp.pixlit.data.api.StatusItem
import ovh.litapp.pixlit.data.api.StatusResponse
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.data.repository.TagCount

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UploadViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<PixelfedRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    
    private lateinit var viewModel: UploadViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { repository.getDefaultStaticTags() } returns listOf("#tag1", "#tag2")
        every { repository.getDefaultStaticCollections() } returns listOf(
            CollectionItem(id = JsonPrimitive("col1"), title = "Collection 1"),
            CollectionItem(id = JsonPrimitive("col2"), title = "Collection 2")
        )
        coEvery { repository.getUserTopTagsAndPosts(any()) } answers {
            Result.success(
                PixelfedRepository.TagsAndPosts(
                    listOf(
                        TagCount("tag1", 2),
                        TagCount("tag2", 1)
                    ),
                    emptyList()
                )
            )
        }
        coEvery { repository.getUserCollections() } returns Result.success(
            listOf(
                CollectionItem(id = JsonPrimitive("col1"), title = "Collection 1"),
                CollectionItem(id = JsonPrimitive("col2"), title = "Collection 2")
            )
        )
        viewModel = UploadViewModel(context, repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addImages updates selectedImageUris`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        val uri2 = mockk<Uri>(relaxed = true)
        val uris = listOf(uri1, uri2)
        
        viewModel.addImages(uris)
        advanceUntilIdle()
        
        assertEquals(uris, viewModel.selectedImageUris.value)
    }

    @Test
    fun `removeImageAt updates selectedImageUris`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        val uri2 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1, uri2))
        advanceUntilIdle()
        
        viewModel.removeImageAt(0)
        advanceUntilIdle()
        
        assertEquals(listOf(uri2), viewModel.selectedImageUris.value)
    }

    @Test
    fun `shiftRight swaps images and updates currentPage`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        val uri2 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1, uri2))
        advanceUntilIdle()
        
        viewModel.shiftRight(0)
        advanceUntilIdle()
        
        assertEquals(listOf(uri2, uri1), viewModel.selectedImageUris.value)
        assertEquals(1, viewModel.currentPage.value)
    }

    @Test
    fun `insertTag updates caption correctly`() = runTest {
        viewModel.insertTag("#photography (12)")
        assertEquals("#photography ", viewModel.captionState.value.text)
    }

    @Test
    fun `insertTagSuggestion replaces the hashtag being typed`() = runTest {
        viewModel.onCaptionChanged(
            TextFieldValue("A photo of #phot", TextRange("A photo of #phot".length))
        )

        viewModel.insertTagSuggestion("photography")

        assertEquals("A photo of #photography ", viewModel.captionState.value.text)
        assertEquals(
            "A photo of #photography ".length,
            viewModel.captionState.value.selection.start
        )
    }

    @Test
    fun `findHashtagContext ignores hashtags embedded in words`() {
        assertEquals(
            HashtagContext("phot", 0, 5),
            findHashtagContext(TextFieldValue("#phot", TextRange(5)))
        )
        assertEquals(
            null,
            findHashtagContext(TextFieldValue("word#phot", TextRange(9)))
        )
    }

    @Test
    fun `toggleCollectionSelection allows selecting 0 1 or multiple collections`() = runTest {
        // Initially 0 collections selected
        assertEquals(emptySet<String>(), viewModel.selectedCollectionIds.value)

        // Select 1 collection
        viewModel.toggleCollectionSelection("col1")
        assertEquals(setOf("col1"), viewModel.selectedCollectionIds.value)

        // Select second collection (multiple)
        viewModel.toggleCollectionSelection("col2")
        assertEquals(setOf("col1", "col2"), viewModel.selectedCollectionIds.value)

        // Deselect col1
        viewModel.toggleCollectionSelection("col1")
        assertEquals(setOf("col2"), viewModel.selectedCollectionIds.value)

        // Deselect col2 -> back to 0 collections
        viewModel.toggleCollectionSelection("col2")
        assertEquals(emptySet<String>(), viewModel.selectedCollectionIds.value)
    }

    @Test
    fun `createAndSelectCollection creates and selects new collection`() = runTest {
        val newCol = CollectionItem(id = JsonPrimitive("new_1"), title = "Architecture")
        coEvery { repository.createCollection("Architecture", null) } returns Result.success(newCol)

        viewModel.createAndSelectCollection("Architecture")
        advanceUntilIdle()

        assert(viewModel.selectedCollectionIds.value.contains("new_1"))
        assert(viewModel.userCollections.value.any { it.getIdString() == "new_1" })
    }

    @Test
    fun `upload passes selected collection IDs to repository`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1))
        viewModel.toggleCollectionSelection("col1")
        viewModel.toggleCollectionSelection("col2")

        coEvery {
            repository.uploadPhotosAndCreateStatus(
                imageUris = any(),
                caption = any(),
                resizeTo8Mb = any(),
                collectionIds = any(),
                placeId = any()
            )
        } returns Result.success(StatusResponse(id = JsonPrimitive("123")))

        viewModel.upload()
        advanceUntilIdle()

        coVerify {
            repository.uploadPhotosAndCreateStatus(
                imageUris = listOf(uri1),
                caption = "",
                resizeTo8Mb = false,
                collectionIds = match { it.containsAll(listOf("col1", "col2")) && it.size == 2 },
                placeId = null
            )
        }
        assertEquals(emptySet<String>(), viewModel.selectedCollectionIds.value)
    }

    @Test
    fun `refreshRecentPosts reloads statuses and updates refreshing state`() = runTest {
        val updatedStatus = StatusItem(id = JsonPrimitive("999"), content = "Refreshed post")
        coEvery { repository.getUserTopTagsAndPosts(forceRefresh = true) } returns Result.success(
            PixelfedRepository.TagsAndPosts(
                topTags = listOf(TagCount("refreshedTag", 5)),
                statuses = listOf(updatedStatus)
            )
        )

        viewModel.refreshRecentPosts()
        advanceUntilIdle()

        coVerify { repository.getUserTopTagsAndPosts(forceRefresh = true) }
        assertEquals(listOf(updatedStatus), viewModel.recentStatuses.value)
        assertEquals(false, viewModel.isRefreshing.value)
    }

    @Test
    fun `selectPlace updates selectedPlace and clears custom location`() = runTest {
        val place = PlaceItem(id = JsonPrimitive("p1"), name = "Rome", country = "Italy")
        viewModel.selectPlace(place)

        assertEquals(place, viewModel.selectedPlace.value)
        assertEquals(null, viewModel.customLocationName.value)
        assertEquals(false, viewModel.isExifAutoDetected.value)
    }

    @Test
    fun `setCustomLocation updates customLocationName and clears selectedPlace`() = runTest {
        val place = PlaceItem(id = JsonPrimitive("p1"), name = "Rome", country = "Italy")
        viewModel.selectPlace(place)

        viewModel.setCustomLocation("Milano")

        assertEquals(null, viewModel.selectedPlace.value)
        assertEquals("Milano", viewModel.customLocationName.value)
        assertEquals(false, viewModel.isExifAutoDetected.value)
    }

    @Test
    fun `clearLocation clears all position selection state`() = runTest {
        val place = PlaceItem(id = JsonPrimitive("p1"), name = "Rome", country = "Italy")
        viewModel.selectPlace(place)

        viewModel.clearLocation()

        assertEquals(null, viewModel.selectedPlace.value)
        assertEquals(null, viewModel.customLocationName.value)
        assertEquals(false, viewModel.isExifAutoDetected.value)
    }

    @Test
    fun `onPlaceSearchQueryChanged performs debounced place search`() = runTest {
        val places = listOf(PlaceItem(id = JsonPrimitive("p10"), name = "Florence", country = "Italy"))
        coEvery { repository.searchPlaces(query = "Flor", lat = null, lng = null) } returns Result.success(places)

        viewModel.onPlaceSearchQueryChanged("Flor")
        advanceTimeBy(301)
        advanceUntilIdle()

        assertEquals(places, viewModel.placeSearchResults.value)
    }

    @Test
    fun `upload with selected place passes placeId to repository`() = runTest {
        val uri1 = mockk<Uri>(relaxed = true)
        viewModel.addImages(listOf(uri1))

        val place = PlaceItem(id = JsonPrimitive("place_99"), name = "Naples")
        viewModel.selectPlace(place)

        coEvery {
            repository.uploadPhotosAndCreateStatus(
                imageUris = any(),
                caption = any(),
                resizeTo8Mb = any(),
                collectionIds = any(),
                placeId = any()
            )
        } returns Result.success(StatusResponse(id = JsonPrimitive("status_123")))

        viewModel.upload()
        advanceUntilIdle()

        coVerify {
            repository.uploadPhotosAndCreateStatus(
                imageUris = listOf(uri1),
                caption = "",
                resizeTo8Mb = false,
                collectionIds = emptyList(),
                placeId = "place_99"
            )
        }
    }
}
