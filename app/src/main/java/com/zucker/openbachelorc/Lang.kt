package com.zucker.openbachelorc

import android.content.Context

/** Which language the UI should use. */
enum class LangMode { SYSTEM, ZH, EN }

/**
 * Two-language support without resource plumbing.
 *
 * The UI is built entirely in code, so instead of threading `R.string` ids
 * through every call site the app keeps the Chinese text as the source of truth
 * and looks up an English translation by that text (see [LangText]). Unknown
 * strings fall back to Chinese, which makes a missing translation harmless.
 *
 * "Default to the system language" is decided once per process in [load]; the
 * activity recreates itself when the user switches, so the value never changes
 * underneath a running view tree.
 */
object Lang {

    private const val PREFS = "obc"
    private const val KEY_MODE = "lang_mode"

    @Volatile
    private var english = false

    /** Resolves the stored preference and caches the outcome. */
    fun load(context: Context) {
        english = resolve(context) == LangMode.EN
    }

    fun isEnglish(): Boolean = english

    /** Stored preference, SYSTEM by default. */
    fun mode(context: Context): LangMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, LangMode.SYSTEM.name)
        return runCatching { LangMode.valueOf(raw!!) }.getOrDefault(LangMode.SYSTEM)
    }

    fun setMode(context: Context, mode: LangMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            // commit(), not apply(): the activity recreates (and may be
            // force-stopped) right away, and an async write can be lost.
            .commit()
        english = resolve(context) == LangMode.EN
    }

    /** The language actually in effect. */
    fun resolve(context: Context): LangMode = when (val stored = mode(context)) {
        LangMode.SYSTEM ->
            if (isSystemChinese(context)) LangMode.ZH else LangMode.EN
        else -> stored
    }

    private fun isSystemChinese(context: Context): Boolean {
        val languages = context.resources.configuration.locales
        val language = if (languages.isEmpty) "en" else languages[0].language
        return language.startsWith("zh", ignoreCase = true)
    }

    /** Translates a Chinese literal into the active language. */
    fun t(zh: String): String {
        if (!english) return zh
        // Trim on both sides: hand-written dictionary keys drift by a space
        // easily, and a miss would silently fall back to Chinese.
        return LangText.EN[zh] ?: LangText.EN[zh.trim()] ?: zh
    }
}
