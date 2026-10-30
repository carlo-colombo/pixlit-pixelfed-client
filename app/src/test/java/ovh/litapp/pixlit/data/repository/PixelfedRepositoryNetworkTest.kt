package ovh.litapp.pixlit.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import io.mockk.mockk
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
import ovh.litapp.pixlit.data.Fetch
import ovh.litapp.pixlit.data.PixelfedHttpException
import ovh.litapp.pixlit.data.api.PixelfedApiFactory
import ovh.litapp.pixlit.data.auth.AuthInterceptor
import ovh.litapp.pixlit.data.auth.SessionState
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.data.db.AppDatabase
import ovh.litapp.pixlit.data.media.MediaUploader
import ovh.litapp.pixlit.data.session.SessionRepository
import ovh.litapp.pixlit.data.status.StatusCache
import ovh.litapp.pixlit.data.status.StatusRemoteSource
import ovh.litapp.pixlit.data.tags.TagSearchCache
import ovh.litapp.pixlit.di.NetworkModule

/**
 * The data layer against a real HTTP server (TEST-01 / 04 F-19): the facade, the per-instance API
 * factory, the auth interceptor and the Room cache are the production instances, only the server is
 * fake. Covers the three behaviours the review found untested — 401 → session expiry, 5xx → typed
 * failure, and the refresh TTL issuing no request.
 */
@RunWith(AndroidJUnit4::class)
class PixelfedRepositoryNetworkTest {

    private lateinit var server: MockWebServer
    private lateinit var database: AppDatabase
    private lateinit var tokenManager: TokenManager
    private lateinit var repository: PixelfedRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gson: Gson = NetworkModule.provideGson()
        server = MockWebServer()
        server.start()

        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        tokenManager = TokenManager(context)
        tokenManager.clear()
        tokenManager.instanceUrl = server.url("/").toString()
        tokenManager.accessToken = "live-token"

        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(tokenManager)).build()
        val apiFactory = PixelfedApiFactory(client, gson)
        val statusCache = StatusCache(database.statusDao(), gson)

        repository = PixelfedRepository(
            context = context,
            tokenManager = tokenManager,
            gson = gson,
            apiFactory = apiFactory,
            sessionRepository = mockk<SessionRepository>(relaxed = true),
            mediaUploader = mockk<MediaUploader>(relaxed = true),
            statusCache = statusCache,
            statusRemoteSource = StatusRemoteSource(apiFactory, statusCache),
            tagSearchCache = TagSearchCache(database, gson)
        )
    }

    @After
    fun tearDown() {
        tokenManager.clear()
        database.close()
        server.shutdown()
    }

    @Test
    fun `a 401 on an authenticated read expires the session and fails the fetch`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody(UNAUTHENTICATED_BODY))

        val result = repository.getUserCollections()

        assertTrue(result is Fetch.Failed)
        assertTrue((result as Fetch.Failed).cause.message.orEmpty().contains("401"))
        // The interceptor is what routes the UI back to login (SEC-04).
        assertEquals(SessionState.Expired, tokenManager.sessionState.value)
        assertEquals(1, server.requestCount)
        assertEquals("Bearer live-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `collections are read from the paginated collections endpoint`() = runTest {
        server.enqueue(MockResponse().setBody(ACCOUNT_BODY))
        server.enqueue(MockResponse().setBody(COLLECTIONS_BODY))

        val result = repository.getUserCollections()

        assertTrue(result is Fetch.Fresh)
        assertEquals(1, result.valueOrNull()?.size)
        assertEquals("/api/v1/accounts/verify_credentials", server.takeRequest().path)
        // The old `accounts/{id}/collections` path 404s on every instance; the real route is
        // `collections/accounts/{id}` and answers a bare array (no paginator envelope).
        assertEquals(
            "/api/pixelfed/v1/collections/accounts/987654321?page=1",
            server.takeRequest().path
        )
    }

    @Test
    fun `a 5xx from place search is a typed failure, not an empty success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"internal_error"}"""))

        val result = repository.searchPlaces("kotor")

        assertTrue(result is Fetch.Failed)
        assertNull(result.valueOrNull())
        val cause = (result as Fetch.Failed).cause
        assertTrue(cause is PixelfedHttpException)
        assertEquals(500, (cause as PixelfedHttpException).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a refresh inside the ttl issues no request`() = runTest {
        server.enqueue(MockResponse().setBody(ACCOUNT_BODY))
        server.enqueue(MockResponse().setBody(STATUSES_BODY))

        val first = repository.getUserTopTagsAndPosts(forceRefresh = false)
        assertEquals(1, first.valueOrNull()?.statuses?.size)
        assertEquals(2, server.requestCount)

        val second = repository.getUserTopTagsAndPosts(forceRefresh = false)

        assertEquals(1, second.valueOrNull()?.statuses?.size)
        // Serving the Room row must not re-enter the network while the TTL is current (DATA-03).
        assertEquals(2, server.requestCount)
    }

    private companion object {
        const val ACCOUNT_BODY = """{"id":987654321}"""
        const val UNAUTHENTICATED_BODY = """{"message":"Unauthenticated."}"""
        const val COLLECTIONS_BODY =
            """[{"id":"740123456789012345","pid":"987654321","title":"Street","visibility":"public","post_count":3,"published_at":"2024-05-01T18:22:31.000000Z"}]"""
        const val STATUSES_BODY =
            """[{"id":"101","content":"Harbour #harbour","created_at":"2024-05-01T18:22:31.000000Z"}]"""
    }
}
