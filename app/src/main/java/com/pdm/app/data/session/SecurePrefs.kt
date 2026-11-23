package com.pdm.app.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** AES-256 EncryptedSharedPreferences backed by Android Keystore. */
object SecurePrefs {
    const val SECURE_PREFS_NAME = "pdm_session_secure"

    @Suppress("DEPRECATION")
    fun open(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return openSecurePrefs(context, masterKey)
    }

    @Suppress("DEPRECATION")
    private fun openSecurePrefs(context: Context, masterKey: MasterKey): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            val isCryptoError = e is java.security.GeneralSecurityException ||
                e is java.security.KeyStoreException ||
                e is java.io.IOException ||
                e.cause is java.security.GeneralSecurityException ||
                e.cause is java.security.KeyStoreException
            if (!isCryptoError) throw e
            context.deleteSharedPreferences(SECURE_PREFS_NAME)
            runCatching {
                val prefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                java.io.File(prefsDir, "$SECURE_PREFS_NAME.xml").delete()
                java.io.File(prefsDir, "$SECURE_PREFS_NAME.xml.bak").delete()
            }
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
