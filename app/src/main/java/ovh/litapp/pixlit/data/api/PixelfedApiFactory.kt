package ovh.litapp.pixlit.data.api

import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and memoizes one [PixelfedApi] per base URL (DATA-06 step 6 / F-15).
 *
 * The base URL is runtime state — the account's instance — so the API cannot be a constructor-provided
 * singleton like [BlueskyApi]. This factory replaces the per-request Retrofit + converter + dynamic
 * proxy construction that used to run on every call; the cache is keyed on the normalized URL so an
 * instance change rebuilds exactly once. Callers pass the instance URL they already resolved from
 * the stored session, keeping this factory free of session state.
 */
@Singleton
class PixelfedApiFactory @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) {

    @Volatile
    private var cached: Pair<String, PixelfedApi>? = null

    /** The API for [baseUrl]; rebuilds only when the normalized URL differs from the cached one. */
    fun get(baseUrl: String): PixelfedApi {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        cached?.takeIf { it.first == normalizedUrl }?.let { return it.second }

        val api = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(client)
            .build()
            .create(PixelfedApi::class.java)
        cached = normalizedUrl to api
        return api
    }
}
