package ovh.litapp.pixlit.data.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) S256 helpers for the authorization-code flow (SEC-03).
 *
 * Only the challenge travels in the authorize URL; the verifier stays in memory and is sent once,
 * with the token exchange, so a redirect whose code was captured by another app cannot be redeemed.
 */
object Pkce {

    /**
     * RFC 7636 §4.1 allows 43–128 characters. 32 random bytes encode to exactly 43 unpadded
     * base64url characters, which is the shortest verifier the spec permits.
     */
    const val VERIFIER_BYTES = 32

    /** The only challenge method Pixelfed's OAuth implementation accepts for a confidential client. */
    const val CODE_CHALLENGE_METHOD = "S256"

    fun generateVerifier(random: SecureRandom = SecureRandom()): String =
        base64Url(ByteArray(VERIFIER_BYTES).also(random::nextBytes))

    /** `BASE64URL(SHA256(ASCII(verifier)))` without padding, per RFC 7636 §4.2. */
    fun s256Challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
