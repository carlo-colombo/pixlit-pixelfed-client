package ovh.litapp.pixlit.data.repository

import java.util.concurrent.atomic.AtomicReference

/**
 * Memoizes the `verify_credentials` account id for one instance+token pair. A different instance or a
 * new token (logout, re-login) misses the entry, so the id is re-resolved instead of surviving a
 * session change.
 */
internal class AccountIdCache {
    private data class Entry(val instanceUrl: String, val accessToken: String, val accountId: String)

    private val entry = AtomicReference<Entry?>(null)

    fun get(instanceUrl: String, accessToken: String): String? = entry.get()
        ?.takeIf { it.instanceUrl == instanceUrl && it.accessToken == accessToken }
        ?.accountId

    fun put(instanceUrl: String, accessToken: String, accountId: String) {
        entry.set(Entry(instanceUrl, accessToken, accountId))
    }
}
