package local.ocrbridge.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Shows [content] in the chosen language, or the system's for "system". The context still wraps
 * the activity, so launching other apps from it keeps working; only its resources change.
 */
@Composable
fun Localized(language: String, content: @Composable () -> Unit) {
    if (language == "system") {
        content()
        return
    }
    val base = LocalContext.current
    val systemConfiguration = LocalConfiguration.current
    val configuration = remember(systemConfiguration, language) {
        Configuration(systemConfiguration).apply { setLocale(Locale.forLanguageTag(language)) }
    }
    val context = remember(base, configuration) {
        val resources = base.createConfigurationContext(configuration).resources
        object : ContextWrapper(base) {
            override fun getResources(): Resources = resources
        } as Context
    }
    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
        content()
    }
}
