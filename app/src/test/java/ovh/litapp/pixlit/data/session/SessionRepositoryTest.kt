package ovh.litapp.pixlit.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ovh.litapp.pixlit.data.api.PixelfedApiFactory
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.di.NetworkModule

/**
 * The token exchange against a real HTTP server (SEC-03 / TEST-01): the PKCE verifier must reach
 * `/oauth/token`, and a verifier-less exchange must not invent the field.
 */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenManager: TokenManager
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        tokenManager = TokenManager(context)
        tokenManager.clear()
        tokenManager.instanceUrl = server.url("/").toString()
        tokenManager.clientId = "client-id"
        tokenManager.clientSecret = "client-secret"
        repository = SessionRepository(
            tokenManager,
            PixelfedApiFactory(OkHttpClient(), NetworkModule.provideGson())
        )
    }

    @After
    fun tearDown() {
        tokenManager.clear()
        server.shutdown()
    }

    @Test
    fun `the token exchange sends the pkce code verifier`() = runTest {
        server.enqueue(MockResponse().setBody(TOKEN_BODY))

        val result = repository.exchangeCodeForToken("code-1", REDIRECT_URI, "verifier-abc")

        assertEquals("token-123", result.getOrNull())
        val request = server.takeRequest()
        assertEquals("/oauth/token", request.path)
        // Retrofit form-encodes the fields in declaration order; the verifier must be one of them.
        assertTrue(request.body.readUtf8().split("&").contains("code_verifier=verifier-abc"))
        assertEquals("token-123", tokenManager.accessToken)
    }

    @Test
    fun `an exchange without a verifier omits the field`() = runTest {
        server.enqueue(MockResponse().setBody(TOKEN_BODY))

        val result = repository.exchangeCodeForToken("code-1", REDIRECT_URI)

        assertEquals("token-123", result.getOrNull())
        assertTrue(server.takeRequest().body.readUtf8().split("&").none { it.startsWith("code_verifier=") })
    }

    @Test
    fun `a rejected exchange stores no token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))

        val result = repository.exchangeCodeForToken("spent-code", REDIRECT_URI, "verifier-abc")

        assertNull(result.getOrNull())
        assertNull(tokenManager.accessToken)
    }

    private companion object {
        const val REDIRECT_URI = "pixlit-dev://oauth"
        const val TOKEN_BODY = """{"access_token":"token-123","token_type":"Bearer","expires_in":3600}"""
    }
}
