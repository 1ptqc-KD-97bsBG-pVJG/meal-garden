package app.mealgarden

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Paper = Color(0xFFF5F0E4)
val Paper2 = Color(0xFFECE5D3)
val CardSurface = Color(0xFFFCF9F2)
val Ink = Color(0xFF2A2A22)
val Muted = Color(0xFF6A6453)
val Faint = Color(0xFF9B937F)
val Line = Color(0xFFDFD6C2)
val Forest = Color(0xFF2F4A2E)
val Leaf = Color(0xFF5E8C3A)
val Lime = Color(0xFFC6DC6B)
val Mist = Color(0xFFE9F1C4)
val Amber = Color(0xFFE0A034)
val AmberLight = Color(0xFFF8E7BD)
val Clay = Color(0xFFC2553D)
val ClayLight = Color(0xFFF3D6CC)
val Ice = Color(0xFF8FB3C9)
val IceLight = Color(0xFFDCEAF1)
val Cream = Paper

object GardenSpace {
    val Tiny = 4.dp
    val Small = 8.dp
    val Card = 12.dp
    val Page = 14.dp
    val Section = 18.dp
    val Touch = 48.dp
}

object GardenShape {
    val Chip = RoundedCornerShape(99.dp)
    val Button = RoundedCornerShape(14.dp)
    val Card = RoundedCornerShape(18.dp)
    val Hero = RoundedCornerShape(22.dp)
    val Panel = RoundedCornerShape(24.dp)
}

object GardenType {
    val Title = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
        fontSize = 28.sp, lineHeight = 30.sp, letterSpacing = (-.3).sp, color = Ink)
    val Section = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
        fontSize = 19.sp, lineHeight = 22.sp, color = Ink)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = Ink)
    val Small = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = Muted)
    val Label = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = .8.sp,
        fontWeight = FontWeight.Bold, color = Faint)
    val Button = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun GardenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Forest, onPrimary = Paper,
            primaryContainer = Mist, onPrimaryContainer = Forest,
            secondary = Leaf, onSecondary = Paper, secondaryContainer = Paper2,
            onSecondaryContainer = Ink, tertiary = Amber, onTertiary = Ink,
            tertiaryContainer = AmberLight, onTertiaryContainer = Ink,
            background = Paper, onBackground = Ink, surface = CardSurface, onSurface = Ink,
            surfaceVariant = Paper2, onSurfaceVariant = Muted, outline = Line,
            outlineVariant = Line, error = Clay, onError = CardSurface,
            errorContainer = ClayLight, onErrorContainer = Clay,
            surfaceContainer = Paper2, surfaceContainerLow = Paper,
            surfaceContainerHigh = CardSurface, surfaceContainerHighest = Paper2,
            surfaceContainerLowest = CardSurface),
        typography = Typography(headlineLarge = GardenType.Title,
            headlineMedium = GardenType.Title, headlineSmall = GardenType.Section,
            titleLarge = GardenType.Section,
            titleMedium = GardenType.Body.copy(fontWeight = FontWeight.SemiBold),
            titleSmall = GardenType.Button, bodyLarge = GardenType.Body,
            bodyMedium = GardenType.Body.copy(fontSize = 14.sp, lineHeight = 19.sp),
            bodySmall = GardenType.Small, labelLarge = GardenType.Button,
            labelMedium = GardenType.Small, labelSmall = GardenType.Label),
        content = content,
    )
}
