package com.pdm.app.data.api

import java.net.URLDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Which second factors a PDM account offers, read from the partial ticket of a TFA login. */
data class TfaMethods(
    val totp: Boolean,
    /** Unused recovery keys; null when the account has no recovery keys set up. */
    val recoveryLeft: Int?,
    val webauthn: Boolean,
    val u2f: Boolean,
    val yubico: Boolean,
) {
    /** The kinds this app can answer: a 6-digit code or a recovery key. */
    val usable: List<TfaKind>
        get() = buildList {
            if (totp) add(TfaKind.TOTP)
            if ((recoveryLeft ?: 0) > 0) add(TfaKind.RECOVERY)
        }

    /** Only factors Android apps cannot use here (security keys bound to the PDM web address). */
    val onlyHardwareKeys: Boolean
        get() = usable.isEmpty() && (webauthn || u2f || yubico)

    companion object {
        /** When the challenge cannot be read: offer both, let the server decide. */
        val UNKNOWN = TfaMethods(totp = true, recoveryLeft = null, webauthn = false, u2f = false, yubico = false)
    }
}

enum class TfaKind { TOTP, RECOVERY }

/**
 * Proxmox TFA (proxmox-tfa / proxmox-auth-api). A password login for an account with a second
 * factor answers with a partial ticket, `PDM:!tfa!<percent-encoded challenge JSON>:<time>::<sig>`.
 * The second request sends that ticket as `tfa-challenge` and the factor as the password:
 * `totp:<code>` or `recovery:<key>` (`webauthn:`/`u2f:` need a browser-bound security key).
 */
object TfaChallenge {
    private const val MARKER = "!tfa!"

    fun methods(partialTicket: String): TfaMethods {
        val start = partialTicket.indexOf(MARKER).takeIf { it >= 0 } ?: return TfaMethods.UNKNOWN
        // Percent-encoding keeps colons out of the data, so the next colon ends it.
        val encoded = partialTicket.substring(start + MARKER.length).substringBefore(':')
        val json = runCatching {
            AppJson.parseToJsonElement(URLDecoder.decode(encoded, "UTF-8")) as? JsonObject
        }.getOrNull() ?: return TfaMethods.UNKNOWN
        fun flag(key: String) = (json[key] as? JsonPrimitive)?.booleanOrNull == true
        fun present(key: String) = json[key].let { it != null && it !is JsonNull }
        val recovery = (json["recovery"] as? JsonArray)?.size
        return TfaMethods(
            totp = flag("totp"),
            recoveryLeft = recovery,
            webauthn = present("webauthn"),
            u2f = present("u2f"),
            yubico = flag("yubico"),
        )
    }

    /** What goes in the password field of the second request. */
    fun response(kind: TfaKind, input: String): String = when (kind) {
        TfaKind.TOTP -> "totp:${input.filter(Char::isDigit)}"
        TfaKind.RECOVERY -> "recovery:${normalizeRecoveryKey(input)}"
    }

    /**
     * PDM compares recovery keys byte for byte, and generates them as lowercase hex in four dashed
     * groups (`1a2b-3c4d-5e6f-7a8b`). Typed or pasted keys come back in that exact form: case
     * folded, spaces and stray dashes dropped, dashes put back. Anything else passes through.
     */
    fun normalizeRecoveryKey(input: String): String {
        val hex = input.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        if (hex.length != 16 || input.any { !it.isLetterOrDigit() && it != '-' && !it.isWhitespace() }) {
            return input.trim()
        }
        return hex.chunked(4).joinToString("-")
    }

    fun isCompleteTotp(input: String) = input.count(Char::isDigit) in 6..8

    fun isCompleteRecoveryKey(input: String) =
        Regex("^[0-9a-f]{4}(-[0-9a-f]{4}){3}$").matches(normalizeRecoveryKey(input))
}
