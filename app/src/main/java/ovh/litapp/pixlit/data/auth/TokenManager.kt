package ovh.litapp.pixlit.data.auth

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenManager @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pixlit_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_INSTANCE_URL = "instance_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_CACHED_TAGS = "cached_tags"
        private const val KEY_TAGS_CACHE_TIME = "tags_cache_time"
    }

    var cachedTagsJson: String?
        get() = prefs.getString(KEY_CACHED_TAGS, null)
        set(value) {
            prefs.edit().putString(KEY_CACHED_TAGS, value).commit()
        }

    var tagsCacheTime: Long
        get() = prefs.getLong(KEY_TAGS_CACHE_TIME, 0L)
        set(value) {
            prefs.edit().putLong(KEY_TAGS_CACHE_TIME, value).commit()
        }

    var instanceUrl: String?
        get() = prefs.getString(KEY_INSTANCE_URL, null)
        set(value) {
            prefs.edit().putString(KEY_INSTANCE_URL, value).commit()
        }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_ACCESS_TOKEN, value).commit()
        }

    var clientId: String?
        get() = prefs.getString(KEY_CLIENT_ID, null)
        set(value) {
            prefs.edit().putString(KEY_CLIENT_ID, value).commit()
        }

    var clientSecret: String?
        get() = prefs.getString(KEY_CLIENT_SECRET, null)
        set(value) {
            prefs.edit().putString(KEY_CLIENT_SECRET, value).commit()
        }

    fun isLoggedIn(): Boolean {
        return !accessToken.isNullOrBlank() && !instanceUrl.isNullOrBlank()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }
}
