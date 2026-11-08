package local.ocrbridge

import android.content.Context

/** The choices that outlive one receipt. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tier: String
        get() = prefs.getString("tier", "small") ?: "small"
        set(value) = prefs.edit().putString("tier", value).apply()

    var language: ReceiptLanguage
        get() = prefs.getString("language", null)
            ?.let { saved -> ReceiptLanguage.entries.firstOrNull { it.name == saved } }
            ?: ReceiptLanguage.AUTO
        set(value) = prefs.edit().putString("language", value.name).apply()

    /** The interface language: "system", or a language tag such as "zh" or "en". */
    var uiLanguage: String
        get() = prefs.getString("ui_language", "system") ?: "system"
        set(value) = prefs.edit().putString("ui_language", value).apply()

    /** Off unless turned on in Settings: records hold receipt photos. */
    var saveRuns: Boolean
        get() = prefs.getBoolean("save_runs", false)
        set(value) = prefs.edit().putBoolean("save_runs", value).apply()
}
