package ovh.litapp.pixlit.data

import retrofit2.Response

/**
 * The outcome of a read that can legitimately fail (DATA-05 / review 04 F-11).
 *
 * It replaces the `Result<T>` returns that laundered failures into an empty list or fabricated
 * data, so a caller can tell "the answer is empty" from "the read broke".
 *
 * - [Fresh] — the read succeeded from the source authoritative for this call: the network, or a
 *   cache entry still inside its TTL. The payload may still be empty; an empty list is a real answer.
 * - [Cached] — the refresh failed and stale data was served instead; [Cached.cause] carries the
 *   failure so the caller can show a "showing cached data" affordance.
 * - [Failed] — nothing usable; the caller must show an error rather than "no results".
 */
sealed interface Fetch<out T> {
    data class Fresh<T>(val value: T) : Fetch<T>
    data class Cached<T>(val value: T, val cause: Throwable) : Fetch<T>
    data class Failed(val cause: Throwable) : Fetch<Nothing>

    /** The payload when there is one, `null` for [Failed]. */
    fun valueOrNull(): T? = when (this) {
        is Fresh -> value
        is Cached -> value
        is Failed -> null
    }

    /** Transforms the payload, keeping the [Fresh]/[Cached] distinction and the failure cause. */
    fun <R> map(transform: (T) -> R): Fetch<R> = when (this) {
        is Fresh -> Fresh(transform(value))
        is Cached -> Cached(transform(value), cause)
        is Failed -> this
    }
}

/** No stored session: every request would be sent unauthenticated, so the read fails up front. */
class NotLoggedInException : Exception("Not logged in")

/**
 * A non-2xx response, typed so callers branch on [code] instead of matching on a message string
 * like `Exception("Tag search failed: 404")`.
 */
class PixelfedHttpException(
    val code: Int,
    val endpoint: String,
    message: String
) : Exception(message)

/**
 * The single HTTP-code → typed-cause mapping for the data layer. [endpoint] is the verb and path of
 * the request so the message stays diagnosable without a stack trace.
 */
fun Response<*>.asPixelfedHttpException(endpoint: String): PixelfedHttpException {
    val detail = runCatching { errorBody()?.string() }.getOrNull()?.takeIf { it.isNotBlank() }
    return PixelfedHttpException(
        code = code(),
        endpoint = endpoint,
        message = if (detail != null) "$endpoint failed: HTTP ${code()} — $detail" else "$endpoint failed: HTTP ${code()}"
    )
}
