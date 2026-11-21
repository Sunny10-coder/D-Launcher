package com.mylittleworld.child.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Warm, high-contrast palette designed for small children and dim screens. */
object Palette {
    val Cream = Color(0xFFFFF7EC)
    val CreamDeep = Color(0xFFFFE9D2)
    val Ink = Color(0xFF3B2E3F)
    val InkSoft = Color(0xFF6B5B70)

    val LearningStart = Color(0xFFFFB37A)
    val LearningEnd = Color(0xFFF27A86)
    val CallStart = Color(0xFF7FD8B8)
    val CallEnd = Color(0xFF45A9A0)
    val VideoStart = Color(0xFFF7A4C4)
    val VideoEnd = Color(0xFFE1688E)
    val CameraStart = Color(0xFF9FC4F8)
    val CameraEnd = Color(0xFF6A8FE0)
    val PlayStart = Color(0xFFC5A8F0)
    val PlayEnd = Color(0xFF8E6BD1)

    fun tileGradient(key: String, theme: String): List<Color> = when (key) {
        "learning" -> if (theme == "ocean") listOf(Color(0xFF8FD3F4), Color(0xFF5B9BD5)) else listOf(LearningStart, LearningEnd)
        "call" -> listOf(CallStart, CallEnd)
        "youtube" -> listOf(VideoStart, VideoEnd)
        "camera" -> listOf(CameraStart, CameraEnd)
        "play" -> listOf(PlayStart, PlayEnd)
        else -> listOf(LearningStart, LearningEnd)
    }

    fun background(theme: String): Brush = when (theme) {
        "ocean" -> Brush.verticalGradient(listOf(Color(0xFFE6F5FB), Color(0xFFFFF7EC)))
        "garden" -> Brush.verticalGradient(listOf(Color(0xFFEAF7E9), Color(0xFFFFF7EC)))
        else -> Brush.verticalGradient(listOf(Cream, CreamDeep))
    }
}

private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(26.dp),
    large = RoundedCornerShape(34.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

private val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun MyLittleWorldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.LearningEnd,
            onPrimary = Color.White,
            secondary = Palette.CallEnd,
            background = Palette.Cream,
            surface = Color.White,
            onSurface = Palette.Ink,
            onBackground = Palette.Ink,
        ),
        shapes = Shapes,
        typography = Typography,
        content = content,
    )
}
