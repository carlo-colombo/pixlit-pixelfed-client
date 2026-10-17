package ovh.litapp.pixlit

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ovh.litapp.pixlit.data.auth.Pkce
import ovh.litapp.pixlit.data.auth.SessionState
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
        every { tokenManager.sessionState } returns MutableStateFlow(SessionState.LoggedOut)
        every { context.getString(R.string.redirect_uri) } returns "pixelfed-app://oauth"
        viewModel = MainViewModel(context, tokenManager, repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `handleIntent with a valid code exchanges it with the verifier behind the challenge`() = runTest {
        coEvery { repository.exchangeCodeForToken(any(), any(), anyNullable()) } returns Result.success("token")
        val authorizeRequest = viewModel.createOAuthState()

        viewModel.handleIntent(oauthRedirect(code = "test_code", state = authorizeRequest.state))
        advanceUntilIdle()

        assertTrue(viewModel.isLoggedIn.value)
        // The verifier the ViewModel kept must be the one that hashes to the challenge it published.
        coVerify {
            repository.exchangeCodeForToken(
                "test_code",
                "pixelfed-app://oauth",
                match { Pkce.s256Challenge(it) == authorizeRequest.codeChallenge }
            )
        }
    }

    @Test
    fun `createOAuthState generates a fresh state and an S256 challenge`() = runTest {
        val first = viewModel.createOAuthState()
        val second = viewModel.createOAuthState()

        assertTrue(first.state.isNotBlank())
        // base64url(SHA256(verifier)) is 43 unpadded characters.
        assertEquals(43, first.codeChallenge.length)
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.codeChallenge, second.codeChallenge)
        assertNotEquals(first.state, first.codeChallenge)
    }

    @Test
    fun `handleIntent with mismatched state reports an error and does not exchange the code`() = runTest {
        viewModel.createOAuthState()

        viewModel.handleIntent(oauthRedirect(code = "test_code", state = "attacker-supplied-state"))
        advanceUntilIdle()

        assertFalse(viewModel.isLoggedIn.value)
        assertNotNull(viewModel.authError.value)
        coVerify(exactly = 0) { repository.exchangeCodeForToken(any(), any(), anyNullable()) }
    }

    @Test
    fun `handleIntent without a state reports an error and does not exchange the code`() = runTest {
        viewModel.createOAuthState()

        viewModel.handleIntent(oauthRedirect(code = "test_code", state = null))
        advanceUntilIdle()

        assertFalse(viewModel.isLoggedIn.value)
        assertNotNull(viewModel.authError.value)
        coVerify(exactly = 0) { repository.exchangeCodeForToken(any(), any(), anyNullable()) }
    }

    @Test
    fun `a consumed state cannot be replayed`() = runTest {
        coEvery { repository.exchangeCodeForToken(any(), any(), anyNullable()) } returns Result.success("token")
        val authorizeRequest = viewModel.createOAuthState()
        val redirect = oauthRedirect(code = "test_code", state = authorizeRequest.state)

        viewModel.handleIntent(redirect)
        advanceUntilIdle()
        viewModel.handleIntent(redirect)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.exchangeCodeForToken(any(), any(), anyNullable()) }
    }

    /**
     * A `pixelfed-app://oauth` redirect with the given query parameters. [state] of `null` stands for
     * a redirect that carries no `state` at all.
     */
    private fun oauthRedirect(code: String?, state: String?): Intent {
        val intent = mockk<Intent>(relaxed = true)
        val uri = mockk<Uri>()
        every { intent.data } returns uri
        every { uri.scheme } returns "pixelfed-app"
        every { uri.host } returns "oauth"
        every { uri.getQueryParameter("code") } returns code
        every { uri.getQueryParameter("state") } returns state
        every { uri.getQueryParameter("error") } returns null
        every { uri.getQueryParameter("error_description") } returns null
        return intent
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
