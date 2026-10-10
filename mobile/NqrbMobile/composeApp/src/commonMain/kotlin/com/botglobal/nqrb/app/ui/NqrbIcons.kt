package com.botglobal.nqrb.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.graphicsLayer

enum class NqrbGlyph {
    Home,
    History,
    Notifications,
    Call,
    People,
    Profile,
    Settings,
    Link,
    Verified,
    Back,
    Language,
    Appearance,
    Microphone,
    MicrophoneOff,
    MinimizeCall,
    Speaker,
    Search,
    Ringtone,
    More,
    Close,
    Chat,
    Send,
    Play,
    Pause,
    Check,
    DoubleCheck,
    Clock,
}

@Composable
fun NqrbIcon(
    glyph: NqrbGlyph,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl && glyph in setOf(NqrbGlyph.Back, NqrbGlyph.Send)
    val semantics = if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }
    Canvas(modifier.then(semantics).graphicsLayer { scaleX = if (mirror) -1f else 1f }) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val center = Offset(size.width / 2f, size.height / 2f)
        when (glyph) {
            NqrbGlyph.Check, NqrbGlyph.DoubleCheck -> {
                fun drawCheck(horizontalOffset: Float) {
                    drawLine(
                        tint,
                        Offset(size.width * (.18f + horizontalOffset), size.height * .52f),
                        Offset(size.width * (.39f + horizontalOffset), size.height * .72f),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        tint,
                        Offset(size.width * (.39f + horizontalOffset), size.height * .72f),
                        Offset(size.width * (.78f + horizontalOffset), size.height * .28f),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
                if (glyph == NqrbGlyph.DoubleCheck) {
                    drawCheck(-.11f)
                    drawCheck(.11f)
                } else {
                    drawCheck(0f)
                }
            }
            NqrbGlyph.Clock -> {
                drawCircle(tint, size.minDimension * .34f, center, style = stroke)
                drawLine(tint, center, Offset(center.x, size.height * .3f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, center, Offset(size.width * .66f, size.height * .58f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            NqrbGlyph.Pause -> {
                drawLine(tint, Offset(size.width * .35f, size.height * .2f), Offset(size.width * .35f, size.height * .8f), 3.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(size.width * .65f, size.height * .2f), Offset(size.width * .65f, size.height * .8f), 3.dp.toPx(), StrokeCap.Round)
            }
            NqrbGlyph.Chat -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(size.width * .16f, size.height * .2f),
                    size = Size(size.width * .68f, size.height * .52f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .12f),
                    style = stroke,
                )
                val tail = Path().apply {
                    moveTo(size.width * .32f, size.height * .7f)
                    lineTo(size.width * .25f, size.height * .84f)
                    lineTo(size.width * .47f, size.height * .72f)
                }
                drawPath(tail, tint, style = stroke)
            }
            NqrbGlyph.Send -> {
                val path = Path().apply {
                    moveTo(size.width * .14f, size.height * .18f)
                    lineTo(size.width * .86f, size.height * .5f)
                    lineTo(size.width * .14f, size.height * .82f)
                    lineTo(size.width * .3f, size.height * .53f)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            NqrbGlyph.Play -> {
                val path = Path().apply {
                    moveTo(size.width * .3f, size.height * .2f)
                    lineTo(size.width * .78f, size.height * .5f)
                    lineTo(size.width * .3f, size.height * .8f)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            NqrbGlyph.Close -> {
                drawLine(tint, Offset(size.width * .24f, size.height * .24f), Offset(size.width * .76f, size.height * .76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .76f, size.height * .24f), Offset(size.width * .24f, size.height * .76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            NqrbGlyph.MinimizeCall -> {
                val midY = center.y
                drawLine(tint, Offset(size.width * .12f, midY), Offset(size.width * .43f, midY), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .32f, size.height * .37f), Offset(size.width * .43f, midY), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .32f, size.height * .63f), Offset(size.width * .43f, midY), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .88f, midY), Offset(size.width * .57f, midY), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .68f, size.height * .37f), Offset(size.width * .57f, midY), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .68f, size.height * .63f), Offset(size.width * .57f, midY), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
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
            NqrbGlyph.Notifications -> {
                drawArc(tint, 200f, 140f, false, Offset(size.width * .27f, size.height * .2f), Size(size.width * .46f, size.height * .42f), style = stroke)
                drawLine(tint, Offset(size.width * .31f, size.height * .56f), Offset(size.width * .2f, size.height * .76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .69f, size.height * .56f), Offset(size.width * .8f, size.height * .76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(size.width * .2f, size.height * .76f), Offset(size.width * .8f, size.height * .76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(tint, size.minDimension * .04f, Offset(center.x, size.height * .84f))
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
                val gearStroke = Stroke(width = 1.65.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val radius = size.minDimension
                val toothStep = kotlin.math.PI.toFloat() / 4f
                val gearOutline = Path()
                repeat(8) { index ->
                    val angle = -kotlin.math.PI.toFloat() / 2f + index * toothStep
                    val points = listOf(
                        angle - toothStep * .32f to radius * .30f,
                        angle - toothStep * .17f to radius * .40f,
                        angle + toothStep * .17f to radius * .40f,
                        angle + toothStep * .32f to radius * .30f,
                    )
                    points.forEachIndexed { pointIndex, (pointAngle, pointRadius) ->
                        val point = Offset(
                            center.x + kotlin.math.cos(pointAngle) * pointRadius,
                            center.y + kotlin.math.sin(pointAngle) * pointRadius,
                        )
                        if (index == 0 && pointIndex == 0) gearOutline.moveTo(point.x, point.y)
                        else gearOutline.lineTo(point.x, point.y)
                    }
                }
                gearOutline.close()
                drawPath(gearOutline, tint, style = gearStroke)
                drawCircle(tint, radius * .105f, center, style = gearStroke)
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
            NqrbGlyph.Microphone, NqrbGlyph.MicrophoneOff -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(size.width * .38f, size.height * .16f),
                    size = Size(size.width * .24f, size.height * .45f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .12f),
                    style = stroke,
                )
                drawArc(tint, 0f, 180f, false, Offset(size.width * .25f, size.height * .37f), Size(size.width * .5f, size.height * .38f), style = stroke)
                drawLine(tint, Offset(center.x, size.height * .74f), Offset(center.x, size.height * .86f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                if (glyph == NqrbGlyph.MicrophoneOff) {
                    drawLine(tint, Offset(size.width * .2f, size.height * .8f), Offset(size.width * .8f, size.height * .2f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
            NqrbGlyph.Speaker -> {
                val path = Path().apply {
                    moveTo(size.width * .18f, size.height * .42f)
                    lineTo(size.width * .36f, size.height * .42f)
                    lineTo(size.width * .56f, size.height * .24f)
                    lineTo(size.width * .56f, size.height * .76f)
                    lineTo(size.width * .36f, size.height * .58f)
                    lineTo(size.width * .18f, size.height * .58f)
                    close()
                }
                drawPath(path, tint, style = stroke)
                drawArc(tint, -48f, 96f, false, Offset(size.width * .5f, size.height * .3f), Size(size.width * .3f, size.height * .4f), style = stroke)
            }
            NqrbGlyph.Search -> {
                drawCircle(tint, size.minDimension * .23f, Offset(size.width * .42f, size.height * .42f), style = stroke)
                drawLine(tint, Offset(size.width * .59f, size.height * .59f), Offset(size.width * .82f, size.height * .82f), stroke.width, StrokeCap.Round)
            }
            NqrbGlyph.Ringtone -> {
                drawArc(tint, 205f, 130f, false, Offset(size.width * .27f, size.height * .22f), Size(size.width * .46f, size.height * .43f), style = stroke)
                drawLine(tint, Offset(size.width * .31f, size.height * .55f), Offset(size.width * .21f, size.height * .76f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * .69f, size.height * .55f), Offset(size.width * .79f, size.height * .76f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * .21f, size.height * .76f), Offset(size.width * .79f, size.height * .76f), stroke.width, StrokeCap.Round)
                drawCircle(tint, size.minDimension * .04f, Offset(center.x, size.height * .82f))
            }
            NqrbGlyph.More -> {
                drawCircle(tint, size.minDimension * .055f, Offset(size.width * .3f, center.y))
                drawCircle(tint, size.minDimension * .055f, center)
                drawCircle(tint, size.minDimension * .055f, Offset(size.width * .7f, center.y))
            }
        }
    }
}
