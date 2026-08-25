package ovh.litapp.pixlit.ui.upload

import android.content.Context
import android.net.Uri
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ovh.litapp.pixlit.data.repository.PixelfedRepository

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
        coEvery { repository.getUserTopTagsAndPosts(any()) } answers {
            Result.success(PixelfedRepository.TagsAndPosts(listOf("#tag1", "#tag2"), emptyList()))
        }
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
}
