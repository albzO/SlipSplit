package local.ocrbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

val Background = Color(0xFFF6F5F2)
val Surface = Color(0xFFFFFFFF)
val Ink = Color(0xFF16181C)
val Muted = Color(0xFF5E636B)
val Line = Color(0xFFE4E2DD)
val Track = Color(0xFFE9E7E2)
val Dark = Color(0xFF16181C)
val OnDarkMuted = Color(0xFFB8BCC4)
val Blue = Color(0xFF2563EB)
val Warn = Color(0xFF9A3F08)
val WarnBackground = Color(0xFFFCEBDD)
/** Prices fixed against the receipt total, on the photo and in the list; no person or item uses it. */
val Fixed = Color(0xFFDC2626)
val Good = Color(0xFF14532D)
val GoodBackground = Color(0xFFE6F4EA)
val Danger = Color(0xFFB42318)
val DangerBackground = Color(0xFFFDECEC)

private val personColors = listOf(
    Color(0xFF2563EB), Color(0xFFC2560C), Color(0xFF0F7A70),
    Color(0xFF7C3AED), Color(0xFFBE185D), Color(0xFF4D7C0F),
)
private val personSoftColors = listOf(
    Color(0xFFE3ECFD), Color(0xFFFCEBDD), Color(0xFFDDF3F0),
    Color(0xFFEEE6FD), Color(0xFFFBE4EE), Color(0xFFEAF3DA),
)

/** People keep their color by id, so removing someone recolors nobody else. */
fun personColor(id: Int) = personColors[id % personColors.size]
fun personSoftColor(id: Int) = personSoftColors[id % personSoftColors.size]
fun groupColor(index: Int) = personColors[index % personColors.size]

@Composable
fun SlipSplitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink,
            onPrimary = Color.White,
            background = Background,
            surface = Surface,
            onSurface = Ink,
            onBackground = Ink,
            surfaceContainerLow = Surface,
        ),
        content = content,
    )
}

/** Every screen's side margin; cards sit on it and their content starts [CardPadding] inside. */
val Gutter = 16.dp
val CardPadding = 14.dp

/**
 * A whole screen: its color reaches under the status bar and its content stays below it. The
 * bottom-most element pads itself with navigationBarsPadding, so its own color fills the
 * gesture bar instead of a strip of the screen color.
 */
@Composable
fun Screen(color: Color = Background, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(color).statusBarsPadding(), content = content)
}

/**
 * A single letter centered by its glyph rather than its line box: the default font padding and
 * line height leave a letter sitting low in a small circle.
 */
val CenteredLetter = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

fun money(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    return sign + "$" + String.format(Locale.US, "%.2f", abs(cents) / 100.0)
}

fun signedMoney(cents: Long) = (if (cents >= 0) "+" else "") + money(cents)
