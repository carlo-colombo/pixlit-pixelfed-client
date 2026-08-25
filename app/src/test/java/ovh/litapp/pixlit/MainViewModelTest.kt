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
        val intent = mockk<Intent>()
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
}
