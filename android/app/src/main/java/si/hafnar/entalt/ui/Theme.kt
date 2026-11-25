package si.hafnar.entalt.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import si.hafnar.entalt.data.Preferences
import si.hafnar.entalt.normalizeAppLanguage
import si.hafnar.entalt.resolveAppLocale

private class LocalizedContextWrapper(
    base: Context,
    private val localizedResources: Resources,
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResources
}

private val Dark = darkColorScheme(
    primary = Color(0xFF94E4CC), onPrimary = Color(0xFF00382D), primaryContainer = Color(0xFF164D40),
    secondary = Color(0xFFDCC49F), onSecondary = Color(0xFF382E1A),
    background = Color(0xFF101715), surface = Color(0xFF101715), onSurface = Color(0xFFE1E9E3),
    surfaceContainer = Color(0xFF1B2420), surfaceContainerHigh = Color(0xFF26312B),
    surfaceVariant = Color(0xFF35463D), onSurfaceVariant = Color(0xFFB9C9BF), outline = Color(0xFF74877C),
)
private val Light = lightColorScheme(primary = Color(0xFF216B56), secondary = Color(0xFF745A32),
    background = Color(0xFFF3F8F2), surface = Color(0xFFF3F8F2), surfaceContainer = Color(0xFFE6EFE6))

@Composable fun EntAltTheme(preferences: Preferences, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val lang = normalizeAppLanguage(preferences.language)
    val localizedConfig = remember(baseConfig, lang) {
        if (lang == "system") {
            baseConfig
        } else {
            val locale = resolveAppLocale(lang, baseConfig)
            Configuration(baseConfig).apply {
                setLocale(locale)
                setLocales(LocaleList(locale))
            }
        }
    }
    val localizedContext = remember(baseContext, localizedConfig, lang) {
        if (lang == "system") {
            baseContext
        } else {
            val resContext = baseContext.createConfigurationContext(localizedConfig)
            LocalizedContextWrapper(baseContext, resContext.resources)
        }
    }
    val dark = when (preferences.theme) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    val colors = if (preferences.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(baseContext) else dynamicLightColorScheme(baseContext)
    } else if (dark) Dark else Light
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfig,
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
