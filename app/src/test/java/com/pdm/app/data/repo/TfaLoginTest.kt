package com.pdm.app.data.repo

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.api.PdmClientFactory
import com.pdm.app.data.api.TfaChallenge
import com.pdm.app.data.api.TfaKind
import com.pdm.app.data.model.LoginOutcome
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.session.SessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two-request TFA login end to end through AuthRepository, against the demo server's "tfa" user. */
class TfaLoginTest {
    private val store = SessionStore(injectedPrefs = FakeSharedPreferences())
    private val auth = AuthRepository(store, PdmClientFactory(store))
    private val config = ServerConfig(host = "demo", username = "tfa", password = "pw", realm = "pdm")

    private suspend fun challenge(): LoginOutcome.NeedsTfa {
        val first = auth.login(config, allowDemo = true)
        assertTrue("password alone must not sign in: $first", first is LoginOutcome.NeedsTfa)
        return first as LoginOutcome.NeedsTfa
    }

    @Test
    fun passwordThenTotpSignsIn() = runTest {
        val need = challenge()
        assertEquals(listOf(TfaKind.TOTP, TfaKind.RECOVERY), need.methods.usable)
        assertEquals(3, need.methods.recoveryLeft)
        val second = auth.login(config, tfaAnswer = TfaChallenge.response(TfaKind.TOTP, "123456"), tfaChallenge = need.partialTicket, allowDemo = true)
        assertTrue("$second", second is LoginOutcome.Success)
    }

    @Test
    fun aRecoveryKeyTypedInCapitalsStillSignsIn() = runTest {
        val need = challenge()
        val answer = TfaChallenge.response(TfaKind.RECOVERY, "1A2B 3C4D 5E6F 7A8B")
        assertTrue(auth.login(config, tfaAnswer = answer, tfaChallenge = need.partialTicket, allowDemo = true) is LoginOutcome.Success)
    }

    @Test
    fun typedDemoHostIsRefused() = runTest {
        val refused = auth.login(config)
        assertTrue("$refused", refused is LoginOutcome.Failed)
    }

    @Test
    fun aWrongCodeAsksAgainAndNeverSignsIn() = runTest {
        val need = challenge()
        val again = auth.login(config, tfaAnswer = "totp:000000", tfaChallenge = need.partialTicket, allowDemo = true)
        assertTrue("$again", again is LoginOutcome.NeedsTfa)
    }
}
