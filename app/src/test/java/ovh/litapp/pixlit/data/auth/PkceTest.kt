package ovh.litapp.pixlit.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-03 PKCE primitives. The S256 vector is the one RFC 7636 Appendix B publishes, so a wrong
 * encoding or digest implementation cannot pass by agreeing with itself.
 */
class PkceTest {

    @Test
    fun `a generated verifier is url-safe and long enough for RFC 7636`() {
        val verifier = Pkce.generateVerifier()

        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(verifier, Pkce.generateVerifier())
    }

    @Test
    fun `the s256 challenge matches the RFC 7636 appendix b vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.s256Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun `a verifier of the minimum length still hashes to a challenge`() {
        val verifier = "a".repeat(43)

        val challenge = Pkce.s256Challenge(verifier)

        assertEquals(43, challenge.length)
        assertTrue(challenge.none { it == '=' || it == '+' || it == '/' })
    }
}
