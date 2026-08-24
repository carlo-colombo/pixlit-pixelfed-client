package ovh.litapp.pixlit.data.auth

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class TokenManagerTest {

    private lateinit var tokenManager: TokenManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        tokenManager = TokenManager(context)
        tokenManager.clear()
    }

    @Test
    fun testLoginState() {
        assertFalse(tokenManager.isLoggedIn())

        tokenManager.instanceUrl = "https://pixelfed.social"
        tokenManager.accessToken = "test_token"

        assertTrue(tokenManager.isLoggedIn())
        assertEquals("https://pixelfed.social", tokenManager.instanceUrl)
        assertEquals("test_token", tokenManager.accessToken)

        tokenManager.clear()
        assertFalse(tokenManager.isLoggedIn())
    }

    @Test
    fun testClientCredentialsCaching() {
        tokenManager.instanceUrl = "https://pixelfed.social"
        tokenManager.clientId = "client_123"
        tokenManager.clientSecret = "secret_456"

        assertEquals("https://pixelfed.social", tokenManager.instanceUrl)
        assertEquals("client_123", tokenManager.clientId)
        assertEquals("secret_456", tokenManager.clientSecret)

        tokenManager.clear()
        assertEquals(null, tokenManager.clientId)
        assertEquals(null, tokenManager.clientSecret)
    }
}
