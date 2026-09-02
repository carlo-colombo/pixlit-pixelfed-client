package ovh.litapp.pixlit

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.R

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val context = mockk<Context>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val tokenManager = mockk<TokenManager>(relaxed = true)
    private val repository = mockk<PixelfedRepository>(relaxed = true)
    
    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { tokenManager.isLoggedIn() } returns false
        every { context.getString(R.string.redirect_uri) } returns "pixelfed-app://oauth"
        viewModel = MainViewModel(context, tokenManager, repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `handleIntent with valid code updates isLoggedIn`() = runTest {
        val intent = mockk<Intent>(relaxed = true)
        val uri = mockk<Uri>()
        every { intent.data } returns uri
        every { uri.scheme } returns "pixelfed-app"
        every { uri.host } returns "oauth"
        every { uri.getQueryParameter("code") } returns "test_code"
        every { uri.getQueryParameter("error") } returns null
        every { uri.getQueryParameter("error_description") } returns null
        
        coEvery { repository.exchangeCodeForToken(any(), any()) } returns Result.success("token")
        
        viewModel.handleIntent(intent)
        
        advanceUntilIdle()
        
        assertTrue(viewModel.isLoggedIn.value)
    }

    @Test
    fun `logout clears token and updates state`() = runTest {
        viewModel.logout()
        
        verify { tokenManager.clear() }
        assertEquals(false, viewModel.isLoggedIn.value)
    }

    @Test
    fun `handleIntent with ACTION_SEND single image updates sharedImageUris`() = runTest {
        val intent = mockk<Intent>()
        val photoUri = mockk<Uri>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.type } returns "image/jpeg"
        every { intent.data } returns null
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns photoUri
        every { intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) } returns photoUri

        viewModel.handleIntent(intent)

        assertEquals(listOf(photoUri), viewModel.sharedImageUris.value)

        viewModel.consumeSharedImageUris()
        assertTrue(viewModel.sharedImageUris.value.isEmpty())
    }

    @Test
    fun `handleIntent with ACTION_SEND_MULTIPLE images updates sharedImageUris`() = runTest {
        val intent = mockk<Intent>()
        val photoUri1 = mockk<Uri>()
        val photoUri2 = mockk<Uri>()
        val uriList = arrayListOf(photoUri1, photoUri2)
        every { intent.action } returns Intent.ACTION_SEND_MULTIPLE
        every { intent.type } returns "image/*"
        every { intent.data } returns null
        every { intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) } returns uriList
        every { intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) } returns uriList

        viewModel.handleIntent(intent)

        assertEquals(listOf(photoUri1, photoUri2), viewModel.sharedImageUris.value)
    }
}
