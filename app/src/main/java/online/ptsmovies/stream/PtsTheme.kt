@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package online.ptsmovies.stream

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Tokens taken from the live PTS Learning Studio stylesheet (education.css).
object Pts {
    val bg: Color @Composable @ReadOnlyComposable get() = if (LocalLight.current) Color(0xFFEEF2FF) else Color.Black
    val card: Color @Composable @ReadOnlyComposable get() = if (LocalLight.current) Color.White else Color(0xFF121212)
    val soft: Color @Composable @ReadOnlyComposable get() = if (LocalLight.current) Color(0xFFF3F4FF) else Color(0xFF202020)
    val text: Color @Composable @ReadOnlyComposable get() = if (LocalWatch.current) Color.White else if (LocalLight.current) Color(0xFF1E1B4B) else Color(0xFFF7F7FF)
    val muted: Color @Composable @ReadOnlyComposable get() = if (LocalLight.current) Color(0xFF575578) else Color(0xFFB0B0B0)
    val muted2: Color @Composable @ReadOnlyComposable get() = muted
    val line: Color @Composable @ReadOnlyComposable get() = if (LocalLight.current) Color(0xFFD2D5EC) else Color(0xFF303030)
    val line2: Color @Composable @ReadOnlyComposable get() = if (LocalLight.current) Color(0xFF8E94BD) else Color(0xFF606060)
    val accent: Color @Composable @ReadOnlyComposable get() = if (LocalWatch.current) Color.White else if (LocalLight.current) Color(0xFF4338CA) else Color(0xFFAAA6FF)
    val onAccent: Color @Composable @ReadOnlyComposable get() = if (LocalWatch.current) Color.Black else if (LocalLight.current) Color.White else Color(0xFF16163F)
    val spark: Color @Composable @ReadOnlyComposable get() = if (LocalLight.current) Color(0xFFC2410C) else Color(0xFFFFB46E)
    val ok: Color @Composable @ReadOnlyComposable get() = if (LocalLight.current) Color(0xFF137453) else Color(0xFF79DFB8)
    val danger: Color @Composable @ReadOnlyComposable get() = if (LocalLight.current) Color(0xFFB42339) else Color(0xFFFF969E)
    val accentSoft: Color @Composable @ReadOnlyComposable get() = accent.copy(alpha = .14f)
    val sparkSoft: Color @Composable @ReadOnlyComposable get() = spark.copy(alpha = .12f)
    val okSoft: Color @Composable @ReadOnlyComposable get() = ok.copy(alpha = .12f)
    val dangerSoft: Color @Composable @ReadOnlyComposable get() = danger.copy(alpha = .14f)
}
private val LocalLight = staticCompositionLocalOf { false }
private val LocalWatch = staticCompositionLocalOf { false }
val LocalPitchBlack = staticCompositionLocalOf { true }

val DmSans = FontFamily(
    Font(R.font.dm_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.opticalSizing(14.sp))),
    Font(R.font.dm_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.opticalSizing(14.sp))),
    Font(R.font.dm_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.opticalSizing(14.sp)))
)

val Nunito = FontFamily(
    Font(R.font.nunito, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.nunito, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    Font(R.font.nunito, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900)))
)

private val PtsType = Typography(
    displaySmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 45.sp, letterSpacing = (-0.9).sp),
    headlineLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 39.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 28.sp, lineHeight = 33.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp)
)

private val PtsShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
)

@Composable
private fun ptsColors(light: Boolean) = (if (light) lightColorScheme() else darkColorScheme()).copy(
    primary = Pts.accent,
    onPrimary = Pts.onAccent,
    primaryContainer = Pts.soft,
    onPrimaryContainer = Pts.text,
    secondary = Pts.spark,
    onSecondary = Pts.onAccent,
    tertiary = Pts.ok,
    background = Pts.bg,
    onBackground = Pts.text,
    surface = Pts.card,
    onSurface = Pts.text,
    surfaceVariant = Pts.soft,
    onSurfaceVariant = Pts.muted,
    outline = Pts.line,
    outlineVariant = Pts.line,
    error = Pts.danger,
    onError = Pts.onAccent,
    errorContainer = Pts.dangerSoft,
    onErrorContainer = Pts.danger
)

@Composable
fun PtsTheme(theme: String = "dark", content: @Composable () -> Unit) {
    val light = theme == "light" || (theme == "system" && !isSystemInDarkTheme())
    val motion = LocalPtsMotion.current ?: rememberSystemMotion()
    CompositionLocalProvider(LocalLight provides light, LocalWatch provides (theme == "watch"), LocalPitchBlack provides !light, LocalPtsMotion provides motion) {
        MaterialTheme(colorScheme = ptsColors(light), typography = PtsType, shapes = PtsShapes, content = content)
    }
}
