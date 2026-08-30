package com.botglobal.nqrb.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class NqrbGlyph {
    Home,
    History,
    Call,
    People,
    Profile,
    Settings,
    Link,
    Verified,
    Back,
    Language,
    Appearance,
}

@Composable
fun NqrbIcon(
    glyph: NqrbGlyph,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val center = Offset(size.width / 2f, size.height / 2f)
        when (glyph) {
            NqrbGlyph.Home -> {
                val path = Path().apply {
                    moveTo(size.width * .18f, size.height * .48f)
                    lineTo(center.x, size.height * .2f)
                    lineTo(size.width * .82f, size.height * .48f)
                    lineTo(size.width * .76f, size.height * .8f)
                    lineTo(size.width * .24f, size.height * .8f)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            NqrbGlyph.History -> {
                drawArc(tint, 38f, 294f, false, Offset(size.width * .18f, size.height * .18f), Size(size.width * .64f, size.height * .64f), style = stroke)
                drawLine(tint, center, Offset(center.x, size.height * .31f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, center, Offset(size.width * .66f, size.height * .58f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .17f, size.height * .31f), Offset(size.width * .17f, size.height * .52f), strokeWidth = stroke.width)
                drawLine(tint, Offset(size.width * .17f, size.height * .31f), Offset(size.width * .37f, size.height * .31f), strokeWidth = stroke.width)
            }
            NqrbGlyph.Call -> {
                val path = Path().apply {
                    moveTo(size.width * .27f, size.height * .2f)
                    cubicTo(size.width * .18f, size.height * .3f, size.width * .31f, size.height * .57f, size.width * .5f, size.height * .72f)
                    cubicTo(size.width * .67f, size.height * .86f, size.width * .79f, size.height * .78f, size.width * .82f, size.height * .67f)
                    lineTo(size.width * .64f, size.height * .56f)
                    lineTo(size.width * .54f, size.height * .65f)
                    cubicTo(size.width * .43f, size.height * .58f, size.width * .36f, size.height * .49f, size.width * .31f, size.height * .37f)
                    lineTo(size.width * .41f, size.height * .29f)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            NqrbGlyph.People -> {
                drawCircle(tint, size.minDimension * .14f, Offset(size.width * .4f, size.height * .35f), style = stroke)
                drawCircle(tint, size.minDimension * .11f, Offset(size.width * .68f, size.height * .4f), style = stroke)
                drawArc(tint, 190f, 160f, false, Offset(size.width * .16f, size.height * .46f), Size(size.width * .48f, size.height * .38f), style = stroke)
                drawArc(tint, 205f, 125f, false, Offset(size.width * .5f, size.height * .5f), Size(size.width * .34f, size.height * .28f), style = stroke)
            }
            NqrbGlyph.Profile -> {
                drawCircle(tint, size.minDimension * .16f, Offset(center.x, size.height * .35f), style = stroke)
                drawArc(tint, 190f, 160f, false, Offset(size.width * .2f, size.height * .48f), Size(size.width * .6f, size.height * .36f), style = stroke)
            }
            NqrbGlyph.Settings -> {
                drawCircle(tint, size.minDimension * .13f, center, style = stroke)
                repeat(8) { index ->
                    val angle = index * kotlin.math.PI.toFloat() / 4f
                    val inner = size.minDimension * .28f
                    val outer = size.minDimension * .39f
                    drawLine(
                        tint,
                        Offset(center.x + kotlin.math.cos(angle) * inner, center.y + kotlin.math.sin(angle) * inner),
                        Offset(center.x + kotlin.math.cos(angle) * outer, center.y + kotlin.math.sin(angle) * outer),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
            }
            NqrbGlyph.Link -> {
                drawArc(tint, 120f, 240f, false, Offset(size.width * .11f, size.height * .28f), Size(size.width * .45f, size.height * .38f), style = stroke)
                drawArc(tint, -60f, 240f, false, Offset(size.width * .44f, size.height * .34f), Size(size.width * .45f, size.height * .38f), style = stroke)
                drawLine(tint, Offset(size.width * .38f, size.height * .57f), Offset(size.width * .62f, size.height * .43f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            NqrbGlyph.Verified -> {
                drawCircle(tint, size.minDimension * .34f, center, style = stroke)
                drawLine(tint, Offset(size.width * .33f, size.height * .51f), Offset(size.width * .45f, size.height * .63f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .45f, size.height * .63f), Offset(size.width * .7f, size.height * .37f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            NqrbGlyph.Back -> {
                drawLine(tint, Offset(size.width * .7f, size.height * .22f), Offset(size.width * .33f, center.y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .33f, center.y), Offset(size.width * .7f, size.height * .78f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            NqrbGlyph.Language -> {
                drawCircle(tint, size.minDimension * .35f, center, style = stroke)
                drawOval(
                    tint,
                    topLeft = Offset(size.width * .36f, size.height * .15f),
                    size = Size(size.width * .28f, size.height * .7f),
                    style = stroke,
                )
                drawLine(tint, Offset(size.width * .17f, center.y), Offset(size.width * .83f, center.y), strokeWidth = stroke.width)
            }
            NqrbGlyph.Appearance -> {
                drawCircle(tint, size.minDimension * .32f, center, style = stroke)
                drawArc(tint, 90f, 180f, true, Offset(size.width * .18f, size.height * .18f), Size(size.width * .64f, size.height * .64f))
            }
        }
    }
}
