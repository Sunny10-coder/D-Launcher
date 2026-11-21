package com.mylittleworld.child.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Lightweight vector icons drawn with Canvas. Keeps the APK small (no icon pack
 * dependency) and gives the launcher a consistent, friendly illustration style.
 */
@Composable
fun TileIcon(kind: TileKind, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        when (kind) {
            TileKind.LEARNING -> {
                val book = Path().apply {
                    moveTo(w * 0.12f, h * 0.22f)
                    quadraticTo(w * 0.35f, h * 0.14f, w * 0.5f, h * 0.3f)
                    quadraticTo(w * 0.65f, h * 0.14f, w * 0.88f, h * 0.22f)
                    lineTo(w * 0.88f, h * 0.8f)
                    quadraticTo(w * 0.65f, h * 0.72f, w * 0.5f, h * 0.86f)
                    quadraticTo(w * 0.35f, h * 0.72f, w * 0.12f, h * 0.8f)
                    close()
                }
                drawPath(book, color, style = stroke)
                drawLine(
                    color,
                    Offset(w * 0.5f, h * 0.3f),
                    Offset(w * 0.5f, h * 0.82f),
                    strokeWidth = w * 0.06f,
                    cap = StrokeCap.Round,
                )
                drawCircle(color, radius = w * 0.045f, center = Offset(w * 0.3f, h * 0.46f))
                drawCircle(color, radius = w * 0.045f, center = Offset(w * 0.68f, h * 0.46f))
            }
            TileKind.CALL -> {
                drawArc(
                    color = color,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(w * 0.1f, h * 0.34f),
                    size = Size(w * 0.8f, h * 0.52f),
                )
                drawCircle(color, radius = w * 0.1f, center = Offset(w * 0.2f, h * 0.72f))
                drawCircle(color, radius = w * 0.1f, center = Offset(w * 0.8f, h * 0.72f))
            }
            TileKind.VIDEO -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.1f, h * 0.24f),
                    size = Size(w * 0.8f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.16f),
                    style = stroke,
                )
                val play = Path().apply {
                    moveTo(w * 0.42f, h * 0.38f)
                    lineTo(w * 0.66f, h * 0.5f)
                    lineTo(w * 0.42f, h * 0.62f)
                    close()
                }
                drawPath(play, color)
            }
            TileKind.CAMERA -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.1f, h * 0.26f),
                    size = Size(w * 0.8f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.16f),
                    style = stroke,
                )
                drawCircle(color, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.52f), style = stroke)
                drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.52f))
                drawLine(
                    color,
                    Offset(w * 0.34f, h * 0.26f),
                    Offset(w * 0.4f, h * 0.16f),
                    strokeWidth = w * 0.08f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(w * 0.4f, h * 0.16f),
                    Offset(w * 0.6f, h * 0.16f),
                    strokeWidth = w * 0.08f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(w * 0.6f, h * 0.16f),
                    Offset(w * 0.66f, h * 0.26f),
                    strokeWidth = w * 0.08f,
                    cap = StrokeCap.Round,
                )
            }
            TileKind.PLAY -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.1f, h * 0.32f),
                    size = Size(w * 0.8f, h * 0.4f),
                    cornerRadius = CornerRadius(w * 0.3f, w * 0.3f),
                    style = stroke,
                )
                drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.32f, h * 0.52f))
                drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.68f, h * 0.52f))
                val dpad = Path().apply {
                    moveTo(w * 0.24f, h * 0.42f)
                    lineTo(w * 0.4f, h * 0.42f)
                    moveTo(w * 0.32f, h * 0.34f)
                    lineTo(w * 0.32f, h * 0.5f)
                }
                drawPath(dpad, color, style = Stroke(width = w * 0.05f, cap = StrokeCap.Round))
            }
        }
    }
}

enum class TileKind { LEARNING, CALL, VIDEO, CAMERA, PLAY }

@Composable
fun GrownUpsIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.24f, h * 0.44f),
            size = Size(w * 0.52f, h * 0.4f),
            cornerRadius = CornerRadius(w * 0.12f),
            style = Stroke(width = w * 0.1f),
        )
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.33f, h * 0.16f),
            size = Size(w * 0.34f, h * 0.4f),
            style = Stroke(width = w * 0.1f, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun ArrowBackIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(
            color,
            Offset(w * 0.2f, h * 0.5f),
            Offset(w * 0.82f, h * 0.5f),
            strokeWidth = w * 0.11f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(w * 0.2f, h * 0.5f),
            Offset(w * 0.46f, h * 0.24f),
            strokeWidth = w * 0.11f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(w * 0.2f, h * 0.5f),
            Offset(w * 0.46f, h * 0.76f),
            strokeWidth = w * 0.11f,
            cap = StrokeCap.Round,
        )
    }
}
