package app.viewfinder.ui.learn

import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.viewfinder.learn.DIAL_ORDER
import app.viewfinder.learn.Mode
import app.viewfinder.ui.Palette
import app.viewfinder.ui.Sans
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/* The D5600's mode dial drawn flat from above, for the big dial a lesson lets you turn. */

internal val Ink = Color(0xFFE9EDE8)

/** Draws with a soft blur (shadows, glows), straight to the platform canvas. */
internal fun DrawScope.blurred(color: Color, radius: Float, block: android.graphics.Paint.(android.graphics.Canvas) -> Unit) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            if (radius > 0f) maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        paint.block(canvas.nativeCanvas)
    }
}


/** Text in face units (drawn under a [unitScale] transform), centred on [at]. */
internal fun DrawScope.text(value: String, at: Offset, sizeUnits: Float, measurer: TextMeasurer, unitScale: Float, color: Color = Ink, weight: Int = 600, rotation: Float = 0f) {
    val style = TextStyle(fontFamily = Sans, fontWeight = FontWeight(weight), fontSize = (sizeUnits * unitScale / density / fontScale).sp, color = color, letterSpacing = 0.4.sp)
    val layout = measurer.measure(value, style)
    // Measured in pixels, drawn in face units: undo the face scale for the text itself.
    withTransform({
        rotate(rotation, at)
        scale(1f / unitScale, 1f / unitScale, at)
    }) {
        drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
    }
}

/** A ridged ring seen face-on (the zoom and focus rings from the front, the command dial from above). */
private fun DrawScope.ridgedRing(center: Offset, outer: Float, inner: Float, ridges: Int, phaseDeg: Float, base: Color) {
    val mid = (outer + inner) / 2
    drawCircle(base, mid, center, style = Stroke(outer - inner))
    rotate(phaseDeg, center) {
        for (i in 0 until ridges) {
            val a = i * 2 * PI / ridges
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            // Ridges facing the light catch it; the rest fall into shadow.
            val lit = ((-ca - sa) * 0.5f + 0.5f).coerceIn(0f, 1f)
            drawLine(Color.White.copy(alpha = 0.04f + 0.12f * lit), center + Offset(ca * inner, sa * inner), center + Offset(ca * outer, sa * outer), 2.2f)
            val b = a + PI / ridges
            drawLine(Color.Black.copy(alpha = 0.7f), center + Offset(cos(b).toFloat() * inner, sin(b).toFloat() * inner), center + Offset(cos(b).toFloat() * outer, sin(b).toFloat() * outer), 2f)
        }
    }
    drawCircle(Color.White.copy(alpha = 0.10f), outer, center, style = Stroke(1.2f))
}

/** Satin metal: a sweep of highlights round a disc, as brushed aluminium catches a ring light. */
private fun metalSweep(center: Offset, dark: Color = Color(0xFF55605A), light: Color = Color(0xFFD9DFDA)) =
    Brush.sweepGradient(listOf(dark, light, dark, light.copy(alpha = 0.8f), dark, light, dark), center)

/**
 * The D5600's mode dial seen from above: knurled edge, a satin top, and the modes printed round it.
 * [rotation] in degrees turns it (see [dialRotationFor]); [selected] is printed in the accent colour.
 */
internal fun DrawScope.drawModeDial(center: Offset, r: Float, rotation: Float, measurer: TextMeasurer, unitScale: Float, selected: Mode?) {
    // Edge
    blurred(Color.Black.copy(alpha = 0.6f), r * 0.12f) { it.drawCircle(center.x, center.y + r * 0.08f, r * 1.02f, this) }
    ridgedRing(center, r, r * 0.86f, 48, rotation, Color(0xFF1C201E))
    // Top
    drawCircle(Brush.radialGradient(listOf(Color(0xFF2A302C), Color(0xFF141716)), center - Offset(r * 0.3f, r * 0.3f), r * 1.2f), r * 0.86f, center)
    drawCircle(metalSweep(center, Color(0xFF151816), Color(0xFF3A423D)), r * 0.86f, center, style = Stroke(r * 0.03f))
    // A raised grip bar across the middle, as on the real dial.
    rotate(rotation, center) {
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF39403B), Color(0xFF101211)), center.y - r * 0.12f, center.y + r * 0.12f),
            topLeft = center - Offset(r * 0.5f, r * 0.11f), size = Size(r, r * 0.22f), cornerRadius = CornerRadius(r * 0.1f),
        )
    }
    DIAL_ORDER.forEachIndexed { i, mode ->
        // Printed round the rim, clockwise from the top.
        val a = (-90f + i * 45f + rotation) * PI.toFloat() / 180f
        val p = center + Offset(cos(a), sin(a)) * (r * 0.66f)
        val on = mode == selected
        val color = when {
            on -> Palette.Accent
            mode == Mode.Auto || mode == Mode.NoFlash -> Color(0xFF8FD18A)
            else -> Ink
        }
        val sizeU = when (mode.mark.length) { 1 -> r * 0.24f; else -> r * 0.12f }
        if (mode == Mode.NoFlash) {
            noFlashIcon(p, r * 0.11f, color)
        } else {
            text(mode.mark, p, sizeU, measurer, unitScale, color, weight = if (on) 800 else 700, rotation = (i * 45f + rotation) % 360f)
        }
    }
}

private fun DrawScope.noFlashIcon(c: Offset, r: Float, color: Color) {
    drawCircle(color, r, c, style = Stroke(r * 0.18f))
    val bolt = Path().apply {
        moveTo(c.x + r * 0.15f, c.y - r * 0.7f); lineTo(c.x - r * 0.35f, c.y + r * 0.1f); lineTo(c.x + r * 0.02f, c.y + r * 0.1f)
        lineTo(c.x - r * 0.15f, c.y + r * 0.7f); lineTo(c.x + r * 0.35f, c.y - r * 0.1f); lineTo(c.x - r * 0.02f, c.y - r * 0.1f); close()
    }
    drawPath(bolt, color)
    drawLine(color, c + Offset(-r * 0.7f, -r * 0.7f), c + Offset(r * 0.7f, r * 0.7f), r * 0.18f)
}

/** Degrees to turn the dial so [mode] sits at the index mark at [indexDeg] (−90 = top, 180 = left). */
internal fun dialRotationFor(mode: Mode, indexDeg: Float): Float = indexDeg - (-90f + DIAL_ORDER.indexOf(mode) * 45f)

