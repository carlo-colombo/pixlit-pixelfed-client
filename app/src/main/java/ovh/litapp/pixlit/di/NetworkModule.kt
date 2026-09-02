package ovh.litapp.pixlit.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import ovh.litapp.pixlit.data.api.PixelfedApi
import ovh.litapp.pixlit.data.api.BlueskyApi
import ovh.litapp.pixlit.data.auth.TokenManager
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setStrictness(Strictness.LENIENT)
        .create()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideBlueskyApi(gson: Gson, client: OkHttpClient): BlueskyApi = Retrofit.Builder()
        .baseUrl("https://public.api.bsky.app/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(BlueskyApi::class.java)
}
