package app.suhani

import android.content.Context
import app.suhani.spikes.BuildConfig

/**
 * API key resolution with rotation support: a key pasted in Settings (stored
 * in app-private prefs) overrides the one baked in at build time, so rotating
 * never needs a rebuild - paste, test, done. Applies from the next
 * conversation (the engine reads the key when a session starts).
 */
object Keys {
    private const val PREFS = "suhani_keys"
    private const val K_SARVAM = "sarvam_api_key"

    fun sarvam(context: Context): String {
        val override = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_SARVAM, null)?.trim()
        return if (!override.isNullOrBlank()) override else BuildConfig.SARVAM_API_KEY
    }

    fun overrideActive(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_SARVAM, null).isNullOrBlank()

    fun setSarvam(context: Context, key: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (key.isNullOrBlank()) remove(K_SARVAM) else putString(K_SARVAM, key.trim()) }
            .apply()
    }
}
