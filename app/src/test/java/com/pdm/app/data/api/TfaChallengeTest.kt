package com.pdm.app.data.api

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TfaChallengeTest {
    /** Built the way proxmox-auth-api does: percent-encoded challenge JSON after `!tfa!`. */
    private fun ticket(json: String) =
        "PDM:!tfa!" + URLEncoder.encode(json, "UTF-8").replace("+", "%20") + ":6716A0B1::c2lnbmF0dXJl"

    @Test
    fun readsTotpAndTheRecoveryKeysLeft() {
        val m = TfaChallenge.methods(ticket("""{"totp":true,"recovery":[0,3,7]}"""))
        assertTrue(m.totp)
        assertEquals(3, m.recoveryLeft)
        assertEquals(listOf(TfaKind.TOTP, TfaKind.RECOVERY), m.usable)
        assertFalse(m.onlyHardwareKeys)
    }

    @Test
    fun spentRecoveryKeysAreNotOffered() {
        val m = TfaChallenge.methods(ticket("""{"totp":true,"recovery":[]}"""))
        assertEquals(0, m.recoveryLeft)
        assertEquals(listOf(TfaKind.TOTP), m.usable)
    }

    @Test
    fun aSecurityKeyOnlyAccountIsRecognised() {
        val m = TfaChallenge.methods(ticket("""{"webauthn":{"publicKey":{"challenge":"abc","rpId":"pdm.example"}}}"""))
        assertTrue(m.webauthn)
        assertTrue(m.usable.isEmpty())
        assertTrue(m.onlyHardwareKeys)
    }

    @Test
    fun anUnreadableChallengeOffersBoth() {
        assertEquals(TfaMethods.UNKNOWN, TfaChallenge.methods("PDM:!tfa!%7Bnot-json:1::x"))
        assertEquals(TfaMethods.UNKNOWN, TfaChallenge.methods("something else entirely"))
        assertNull(TfaMethods.UNKNOWN.recoveryLeft)
    }

    @Test
    fun answersUseTheServersPrefixes() {
        assertEquals("totp:123456", TfaChallenge.response(TfaKind.TOTP, " 123 456 "))
        assertEquals("recovery:1a2b-3c4d-5e6f-7a8b", TfaChallenge.response(TfaKind.RECOVERY, "1A2B 3C4D 5E6F 7A8B"))
    }

    @Test
    fun recoveryKeysAreNormalisedToTheExactStoredForm() {
        assertEquals("1a2b-3c4d-5e6f-7a8b", TfaChallenge.normalizeRecoveryKey("1a2b3c4d5e6f7a8b"))
        assertEquals("1a2b-3c4d-5e6f-7a8b", TfaChallenge.normalizeRecoveryKey(" 1A2B-3C4D-5E6F-7A8B\n"))
        // Not a key we recognise: left alone (trimmed) for the server to judge.
        assertEquals("not a key", TfaChallenge.normalizeRecoveryKey(" not a key "))
        assertTrue(TfaChallenge.isCompleteRecoveryKey("1A2B 3C4D 5E6F 7A8B"))
        assertFalse(TfaChallenge.isCompleteRecoveryKey("1a2b-3c4d"))
        assertTrue(TfaChallenge.isCompleteTotp("123456"))
        assertFalse(TfaChallenge.isCompleteTotp("12345"))
    }
}
