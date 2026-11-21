package app.viewfinder.ui.learn

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import app.viewfinder.learn.Control
import app.viewfinder.learn.DIAL_ORDER
import app.viewfinder.learn.Mode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The D5600 as an anatomy figure: an isometric drawing of solid parts on a turntable, in the anatomy skill's
 * style. Every part is a convex solid (a bevelled prism or drum) painted far to near; its visible faces are filled
 * in a few close tones by facing, a lighter top, then its outline and the details that sit on it (seams, ticks,
 * knurls, dots, engraved text) are drawn in its own slot, so nearer parts cover them naturally. The highlighted
 * control is drawn "lit" (white lines), the one thing to watch.
 *
 * Sizes are millimetres, from the real camera (124 × 97 × 70 mm). Axes: x to the right as you hold it, y up,
 * z towards you (the back faces +z, the lens −z). Parts meet flush, never sunk into each other.
 */

internal data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): V3 { val l = sqrt(dot(this)); return if (l == 0f) this else this * (1f / l) }

    companion object {
        val X = V3(1f, 0f, 0f)
        val Y = V3(0f, 1f, 0f)
        val Z = V3(0f, 0f, 1f)
        val ZERO = V3(0f, 0f, 0f)
    }
}

/** Line tones, as in the anatomy kit: faint grooves, lo minor marks, mid structure, hi emphasis, lit the accent. */
internal enum class Tone { Faint, Lo, Mid, Hi, Lit }

/** A detail drawn on its part's surface, shown only while that surface faces you. */
internal sealed interface Mark { val normal: V3 }
internal class LineMark(val pts: List<V3>, override val normal: V3, val tone: Tone = Tone.Lo, val width: Float = 1f) : Mark
internal class DotMark(val at: V3, override val normal: V3, val radius: Float, val alpha: Float = 0.32f) : Mark
internal class FillMark(val pts: List<V3>, override val normal: V3, val color: Int, val edge: Tone? = null) : Mark
internal class TextMark(val text: String, val at: V3, val right: V3, val up: V3, val height: Float, val tone: Tone = Tone.Mid, val color: Int? = null) : Mark {
    override val normal: V3 = right.cross(up).norm()
}

/** How a part moves when its control acts out what it does. */
internal sealed interface Move {
    data object Still : Move
    /** Pushed in along [dir] by [depth] mm. */
    data class Press(val dir: V3, val depth: Float) : Move
    /** Turned about [axis] through [pivot] by [degrees]: a dial clicking round, a lever flicked, a screen swung out. */
    data class Turn(val axis: V3, val pivot: V3, val degrees: Float) : Move
}

/**
 * One convex part: vertices, faces (index lists with an outward normal), which faces get their own outline (caps,
 * bevels), and the marks on it. [lift] lightens its fills (metal). [static] parts stay put while the turntable and
 * camera turn; [base] parts are painted before everything that stands on them.
 */
internal class Solid(
    val verts: FloatArray,
    val faces: List<IntArray>,
    val normals: List<V3>,
    val curved: BooleanArray,
    val outlined: BooleanArray,
    val marks: List<Mark> = emptyList(),
    val control: Control? = null,
    val move: Move = Move.Still,
    val lift: Float = 0f,
    val detail: Boolean = false,
    val static: Boolean = false,
    val base: Boolean = false,
    val edge: Tone = Tone.Mid,
) {
    val centre: V3 = run {
        var x = 0f; var y = 0f; var z = 0f
        val n = verts.size / 3
        for (i in 0 until n) { x += verts[i * 3]; y += verts[i * 3 + 1]; z += verts[i * 3 + 2] }
        V3(x / n, y / n, z / n)
    }

    fun with(marks: List<Mark> = this.marks, control: Control? = this.control, move: Move = this.move, lift: Float = this.lift, detail: Boolean = this.detail, static: Boolean = this.static, base: Boolean = this.base, edge: Tone = this.edge) =
        Solid(verts, faces, normals, curved, outlined, marks, control, move, lift, detail, static, base, edge)
}

/** Where a control is, which way it faces, and the view (yaw, pitch) that shows it best. */
internal class Spot(val control: Control, val at: V3, val normal: V3, val yaw: Float, val pitch: Float)

/** [floor] is the centre of the turntable's underside, where its halo sits. */
internal class Rig(val solids: List<Solid>, val spots: List<Spot>, val floor: V3 = V3(-4f, -56f, -12f), val floorRadius: Float = 90f)

// ——— Building solids ———

/** A local frame: origin, two plan axes and the axis a plan is extruded along (u × v = w). */
private class Frame(val o: V3, val u: V3, val v: V3, val w: V3) {
    fun at(a: Float, b: Float, c: Float) = o + u * a + v * b + w * c
    fun dir(a: Float, b: Float, c: Float) = (u * a + v * b + w * c).norm()

    companion object {
        /** Extruded along +z, plan in x (right) and y (up). */
        fun alongZ(o: V3) = Frame(o, V3.X, V3.Y, V3.Z)
        /** Extruded along −z (out of the front), plan in x and y. */
        fun alongMinusZ(o: V3) = Frame(o, V3.X * -1f, V3.Y, V3.Z * -1f)
        /** Extruded upwards, plan in x (right) and −z (towards the lens). */
        fun alongY(o: V3) = Frame(o, V3.X, V3.Z * -1f, V3.Y)
        /** Extruded along +x, plan in −z and y. */
        fun alongX(o: V3) = Frame(o, V3.Z * -1f, V3.Y, V3.X)
        fun alongMinusX(o: V3) = Frame(o, V3.Z, V3.Y, V3.X * -1f)
    }
}

/** A rounded rectangle, anticlockwise, centred on (cx, cy). */
private fun roundRect(cx: Float, cy: Float, w: Float, h: Float, r: Float, perCorner: Int = 5): List<Pair<Float, Float>> {
    val rr = minOf(r, w / 2, h / 2)
    val out = ArrayList<Pair<Float, Float>>()
    val corners = listOf(Triple(cx + w / 2 - rr, cy - h / 2 + rr, -90.0), Triple(cx + w / 2 - rr, cy + h / 2 - rr, 0.0), Triple(cx - w / 2 + rr, cy + h / 2 - rr, 90.0), Triple(cx - w / 2 + rr, cy - h / 2 + rr, 180.0))
    for ((x, y, start) in corners) for (k in 0..perCorner) {
        val a = Math.toRadians(start + 90.0 * k / perCorner)
        out += (x + rr * cos(a).toFloat()) to (y + rr * sin(a).toFloat())
    }
    return out
}

/** A convex polygon with its corners rounded, anticlockwise. */
private fun roundPoly(pts: List<Pair<Float, Float>>, r: Float, perCorner: Int = 5): List<Pair<Float, Float>> {
    val out = ArrayList<Pair<Float, Float>>()
    val n = pts.size
    for (i in 0 until n) {
        val p = pts[i]; val a = pts[(i - 1 + n) % n]; val b = pts[(i + 1) % n]
        fun unit(dx: Float, dy: Float): Pair<Float, Float> { val l = sqrt(dx * dx + dy * dy); return (dx / l) to (dy / l) }
        val (ax, ay) = unit(a.first - p.first, a.second - p.second)
        val (bx, by) = unit(b.first - p.first, b.second - p.second)
        val half = Math.acos((ax * bx + ay * by).toDouble().coerceIn(-1.0, 1.0)) / 2
        val d = (r / Math.tan(half)).toFloat()
        val s = (p.first + ax * d) to (p.second + ay * d)
        val e = (p.first + bx * d) to (p.second + by * d)
        // Centre of the corner arc, along the bisector.
        val (mx, my) = unit(ax + bx, ay + by)
        val cd = (r / sin(half)).toFloat()
        val c = (p.first + mx * cd) to (p.second + my * cd)
        val a0 = Math.atan2((s.second - c.second).toDouble(), (s.first - c.first).toDouble())
        var a1 = Math.atan2((e.second - c.second).toDouble(), (e.first - c.first).toDouble())
        while (a1 < a0) a1 += 2 * PI
        if (a1 - a0 > PI) a1 -= 2 * PI
        for (k in 0..perCorner) {
            val t = a0 + (a1 - a0) * k / perCorner
            out += (c.first + r * cos(t).toFloat()) to (c.second + r * sin(t).toFloat())
        }
    }
    return out
}

private fun circle(r: Float, n: Int): List<Pair<Float, Float>> = (0 until n).map { val a = 2 * PI * it / n; (r * cos(a).toFloat()) to (r * sin(a).toFloat()) }

private class SolidBuilder {
    val v = ArrayList<Float>()
    val faces = ArrayList<IntArray>()
    val normals = ArrayList<V3>()
    val curved = ArrayList<Boolean>()
    val outlined = ArrayList<Boolean>()
    fun vert(p: V3): Int { v += p.x; v += p.y; v += p.z; return v.size / 3 - 1 }
    fun face(idx: IntArray, n: V3, isCurved: Boolean, outline: Boolean) { faces += idx; normals += n.norm(); curved += isCurved; outlined += outline }
    fun build() = Solid(v.toFloatArray(), faces, normals, curved.toBooleanArray(), outlined.toBooleanArray())
}

/** Vertex normals of a convex anticlockwise plan, for insetting it. */
private fun inset(plan: List<Pair<Float, Float>>, d: Float): List<Pair<Float, Float>> {
    val n = plan.size
    return plan.indices.map { i ->
        val a = plan[(i - 1 + n) % n]; val p = plan[i]; val b = plan[(i + 1) % n]
        fun nrm(x0: Float, y0: Float, x1: Float, y1: Float): Pair<Float, Float> { val dx = x1 - x0; val dy = y1 - y0; val l = sqrt(dx * dx + dy * dy); return (dy / l) to (-dx / l) }
        val (n1x, n1y) = nrm(a.first, a.second, p.first, p.second)
        val (n2x, n2y) = nrm(p.first, p.second, b.first, b.second)
        var mx = n1x + n2x; var my = n1y + n2y
        val l = sqrt(mx * mx + my * my); mx /= l; my /= l
        (p.first - mx * d) to (p.second - my * d)
    }
}

/**
 * A plan extruded along a frame's w axis for [length], with a [bevel] chamfered round both caps (a highlight ring,
 * as on moulded and machined parts). [taperTo] scales the far end's plan about the frame origin (the prism hump).
 * [smooth] marks the sides curved, for drums.
 */
private fun prism(f: Frame, plan: List<Pair<Float, Float>>, length: Float, bevel: Float = 0f, smooth: Boolean = false): Solid {
    val b = SolidBuilder()
    val n = plan.size
    val bv = minOf(bevel, length / 3f)
    val inner = if (bv > 0f) inset(plan, bv) else plan
    fun ring(p: List<Pair<Float, Float>>, w: Float) = IntArray(n) { b.vert(f.at(p[it].first, p[it].second, w)) }
    val capLo = ring(inner, 0f)
    val sideLo = if (bv > 0f) ring(plan, bv) else capLo
    val sideHi = if (bv > 0f) ring(plan, length - bv) else ring(plan, length)
    val capHi = if (bv > 0f) ring(inner, length) else sideHi
    for (i in 0 until n) {
        val j = (i + 1) % n
        val dx = plan[j].first - plan[i].first; val dy = plan[j].second - plan[i].second
        val out = f.dir(dy, -dx, 0f)
        b.face(intArrayOf(sideLo[i], sideLo[j], sideHi[j], sideHi[i]), out, smooth, false)
        if (bv > 0f) {
            b.face(intArrayOf(capLo[i], capLo[j], sideLo[j], sideLo[i]), (out + f.w * -1f).norm(), smooth, false)
            b.face(intArrayOf(sideHi[i], sideHi[j], capHi[j], capHi[i]), (out + f.w).norm(), smooth, false)
        }
    }
    b.face(capLo.reversedArray(), f.w * -1f, false, true)
    b.face(capHi, f.w, false, true)
    return b.build()
}

/** A round drum (button, dial, lens ring) on [axis] from [start], [segments] round. */
private fun drum(start: V3, axis: V3, r: Float, length: Float, bevel: Float = 0f, segments: Int = segmentsFor(r)): Solid {
    val w = axis.norm()
    val helper = if (abs(w.y) < 0.9f) V3.Y else V3.X
    val u = helper.cross(w).norm()
    val v = w.cross(u).norm()
    return prism(Frame(start, u, v, w), circle(r, segments), length, bevel, smooth = true)
}

private fun segmentsFor(r: Float) = when {
    r < 4f -> 18
    r < 10f -> 28
    r < 20f -> 40
    else -> 64
}

/** Knurl ridges round a drum's side: short lines along the axis, fading as they turn away. */
private fun knurl(start: V3, axis: V3, r: Float, length: Float, count: Int, tone: Tone = Tone.Lo, inset: Float = 0.6f): List<Mark> {
    val w = axis.norm()
    val helper = if (abs(w.y) < 0.9f) V3.Y else V3.X
    val u = helper.cross(w).norm()
    val v = w.cross(u).norm()
    return (0 until count).map { i ->
        val a = 2 * PI * i / count
        val radial = (u * cos(a).toFloat() + v * sin(a).toFloat()).norm()
        LineMark(listOf(start + radial * (r + 0.02f) + w * inset, start + radial * (r + 0.02f) + w * (length - inset)), radial, tone, 0.8f)
    }
}

/** A circle of [r] on a plane (centre, normal), as a closed line. */
private fun ringLine(c: V3, n: V3, r: Float, tone: Tone, segments: Int = 48, width: Float = 1f): LineMark {
    val w = n.norm()
    val helper = if (abs(w.y) < 0.9f) V3.Y else V3.X
    val u = helper.cross(w).norm()
    val v = w.cross(u).norm()
    val pts = (0..segments).map { val a = 2 * PI * it / segments; c + u * (r * cos(a).toFloat()) + v * (r * sin(a).toFloat()) }
    return LineMark(pts, w, tone, width)
}

private fun disc(c: V3, n: V3, r: Float, color: Int, edge: Tone? = null, segments: Int = 40): FillMark {
    val w = n.norm()
    val helper = if (abs(w.y) < 0.9f) V3.Y else V3.X
    val u = helper.cross(w).norm()
    val v = w.cross(u).norm()
    return FillMark((0 until segments).map { val a = 2 * PI * it / segments; c + u * (r * cos(a).toFloat()) + v * (r * sin(a).toFloat()) }, w, color, edge)
}

private fun dotGrid(origin: V3, a: V3, b: V3, na: Int, nb: Int, normal: V3, r: Float = 0.35f, alpha: Float = 0.22f): List<Mark> =
    (0 until na).flatMap { i -> (0 until nb).map { j -> DotMark(origin + a * i.toFloat() + b * j.toFloat(), normal, r, alpha) } }

// ——— The camera ———

private const val GLASS = 0xFF07090A.toInt()
private const val SCREEN_GLASS = 0xFF0A0C0B.toInt()
private const val RED = 0xFFC9473A.toInt()
private const val AMBER = 0xFF6E5A2E.toInt()

/** The lens axis, left of centre: the grip takes the right. */
private val LENS = V3(-11f, -2f, -20f)

/** A round button facing [dir] on the surface at [at]; it presses in when it acts. */
private fun button(control: Control?, at: V3, dir: V3, r: Float, height: Float = 1.6f, label: String? = null, labelUp: V3 = V3.Y, labelSize: Float = 2.2f, labelColor: Int? = null, extra: List<Mark> = emptyList()): Solid {
    val top = at + dir * (height + 0.03f)
    // Reading left to right as you face the button: right × up points out of it.
    val right = labelUp.cross(dir).norm()
    val marks = buildList {
        addAll(extra.map { m -> if (m is LineMark) LineMark(m.pts.map { it + dir * (height + 0.03f) }, dir, m.tone, m.width) else m })
        if (label != null) add(TextMark(label, top, right, labelUp, labelSize, Tone.Mid, labelColor))
    }
    return drum(at, dir, r, height, bevel = if (r > 3f) 0.4f else 0f).with(marks = marks, control = control, move = Move.Press(dir, height * 0.55f), detail = true)
}

/** The D5600 with its 18–55 kit lens, standing on a turntable; [mode] is what the mode dial shows. */
internal fun buildD5600(mode: Mode): Rig {
    val s = ArrayList<Solid>()
    val up = V3.Y
    val back = V3.Z
    val front = V3.Z * -1f

    // — Turntable: a fixed plinth on four feet, and the platter that turns, with a degree ruler round its rim —
    val tc = V3(-4f, 0f, -12f)
    s += drum(V3(tc.x, -50f, tc.z), up, 90f, 6f, bevel = 1.2f, segments = 96).with(
        static = true, base = true, edge = Tone.Lo,
        marks = (0 until 8).map { k -> val a = 2 * PI * (k + 0.5) / 8; DotMark(V3(tc.x + 87f * cos(a).toFloat(), -43.95f, tc.z + 87f * sin(a).toFloat()), up, 0.55f, 0.5f) } +
            (0 until 8).map { k -> val a = 2 * PI * (k + 0.5) / 8; ringLine(V3(tc.x + 87f * cos(a).toFloat(), -43.95f, tc.z + 87f * sin(a).toFloat()), up, 1.8f, Tone.Lo, 16, 0.8f) },
    )
    for (k in 0 until 4) {
        val a = 2 * PI * (k + 0.5) / 4
        s += drum(V3(tc.x + 74f * cos(a).toFloat(), -54f, tc.z + 74f * sin(a).toFloat()), up, 7f, 4f, bevel = 0.6f).with(static = true, base = true, edge = Tone.Lo)
    }
    val ticks = ArrayList<Mark>()
    for (k in 0 until 72) {
        val a = 2 * PI * k / 72
        val dir = V3(cos(a).toFloat(), 0f, sin(a).toFloat())
        val major = k % 6 == 0
        ticks += LineMark(listOf(tc + dir * 83.6f + V3(0f, -37.95f, 0f), tc + dir * (if (major) 79f else 81.2f) + V3(0f, -37.95f, 0f)), up, if (major) Tone.Mid else Tone.Lo, 0.8f)
        if (major) {
            val along = V3(-sin(a).toFloat(), 0f, cos(a).toFloat())
            ticks += TextMark("${(k * 5) % 360}", tc + dir * 76f + V3(0f, -37.95f, 0f), along * -1f, dir, 2.6f, Tone.Lo)
        }
    }
    ticks += ringLine(V3(tc.x, -37.95f, tc.z), up, 83.6f, Tone.Lo, 96, 0.8f)
    s += drum(V3(tc.x, -44f, tc.z), up, 84f, 6f, bevel = 1f, segments = 96).with(base = true, edge = Tone.Lo, marks = ticks)
    // A label plate near the rim, with its engraving and two screws.
    val plateAt = tc + V3(-44f, -38f, 50f)
    s += prism(Frame.alongY(plateAt), roundRect(0f, 0f, 30f, 10f, 1.2f, 3), 0.8f, bevel = 0.3f).with(
        base = true, edge = Tone.Lo,
        marks = listOf(
            TextMark("NIKON D5600", plateAt + V3(0f, 0.85f, -1.6f), V3.X, V3.Z * -1f, 2.2f, Tone.Mid),
            LineMark(listOf(plateAt + V3(-11f, 0.85f, 2f), plateAt + V3(8f, 0.85f, 2f)), up, Tone.Faint, 0.7f),
            LineMark(listOf(plateAt + V3(-11f, 0.85f, 3.6f), plateAt + V3(3f, 0.85f, 3.6f)), up, Tone.Faint, 0.7f),
            DotMark(plateAt + V3(-13.2f, 0.85f, 0f), up, 0.5f, 0.6f),
            DotMark(plateAt + V3(13.2f, 0.85f, 0f), up, 0.5f, 0.6f),
        ),
    )

    // — Shells: body, grip and prism hump, meeting flush —
    s += prism(Frame.alongZ(V3(-11f, 0f, -20f)), roundRect(0f, 0f, 102f, 76f, 9f), 40f, bevel = 1.4f).with(
        marks = buildList {
            // Speaker grille on the top left; a seam across the top and down the side; the port cover on the left.
            addAll(dotGrid(V3(-58f, 38.05f, 3f), V3(2.4f, 0f, 0f), V3(0f, 0f, 2.4f), 7, 4, up, 0.45f, 0.4f))
            add(LineMark(listOf(V3(-60.6f, 38.05f, 13f), V3(-36f, 38.05f, 13f)), up, Tone.Faint))
            add(LineMark(listOf(V3(-62.05f, 36.6f, 13f), V3(-62.05f, -36.6f, 13f)), V3.X * -1f, Tone.Faint))
            add(LineMark(roundRect(0f, 0f, 22f, 36f, 3f, 3).let { p -> p + p.first() }.map { (a, b) -> V3(-62.05f, -4f + b, -4f + a) }, V3.X * -1f, Tone.Lo))
            add(LineMark(listOf(V3(-62.05f, 12f, -12f), V3(-62.05f, 12f, 4f)), V3.X * -1f, Tone.Faint))
            // Front: the mount's index dot.
            add(DotMark(V3(-11f + 0f, 30.5f, -20.05f), front, 0.8f, 0.9f))
        },
    )
    s += prism(Frame.alongY(V3(51f, -38f, -9f)), roundRect(0f, 0f, 22f, 58f, 9f), 76f, bevel = 1.6f).with(
        marks = buildList {
            // Pebbled rubber on the front, the side and the thumb rest.
            addAll(dotGrid(V3(43f, -33f, -38.05f), V3(2.6f, 0f, 0f), V3(0f, 2.6f, 0f), 7, 23, front))
            addAll(dotGrid(V3(62.05f, -33f, -34f), V3(0f, 0f, 3f), V3(0f, 3f, 0f), 17, 20, V3.X))
            addAll(dotGrid(V3(46f, 4f, 20.05f), V3(2.6f, 0f, 0f), V3(0f, 2.6f, 0f), 6, 11, back))
            // The red accent along the top of the grip's face.
            add(FillMark(listOf(V3(42f, 30f, -38.06f), V3(60f, 32.6f, -38.06f), V3(60f, 34.2f, -38.06f), V3(42f, 31.6f, -38.06f)), front, RED))
        },
    )
    val hump = roundPoly(listOf(-25f to 0f, 25f to 0f, 15f to 19f, -15f to 19f), 4f)
    s += prism(Frame.alongZ(V3(-11f, 38f, -19f)), hump, 36f, bevel = 1.2f).with(
        marks = listOf(
            // The pop-up flash's seam across the front, and microphone holes on top.
            LineMark(listOf(V3(-11f - 18.4f, 50f, -19.05f), V3(-11f + 18.4f, 50f, -19.05f)), front, Tone.Lo),
        ) + dotGrid(V3(-22.6f, 57.05f, -16.4f), V3(1.6f, 0f, 0f), V3(0f, 0f, 1.6f), 3, 3, up, 0.4f, 0.45f) +
            dotGrid(V3(-2.6f, 57.05f, -16.4f), V3(1.6f, 0f, 0f), V3(0f, 0f, 1.6f), 3, 3, up, 0.4f, 0.45f),
    )
    // Hot shoe: a plate with two bright rails and the flash contacts.
    s += prism(Frame.alongY(V3(-11f, 57f, 0f)), roundRect(0f, 0f, 20f, 20f, 1.4f, 2), 1.2f).with(
        lift = 0.5f, detail = true,
        marks = listOf(DotMark(V3(-11f, 58.25f, 0f), up, 1.3f, 0.7f)) + listOf(-4f to -5f, 4f to -5f, -4f to 5f, 4f to 5f).map { (dx, dz) -> DotMark(V3(-11f + dx, 58.25f, dz), up, 0.5f, 0.6f) },
    )
    for (x in listOf(-20.5f, -1.5f)) s += prism(Frame.alongZ(V3(x, 59.3f, -9.5f)), roundRect(0f, 0f, 2.2f, 2f, 0.5f, 2), 19f).with(lift = 0.9f, detail = true, edge = Tone.Hi)

    // — Back —
    s += prism(Frame.alongZ(V3(-11f, 30f, 20f)), roundRect(0f, 0f, 30f, 22f, 6f), 6.5f, bevel = 1.4f).with(
        control = Control.Viewfinder, detail = true,
        marks = listOf(
            FillMark(roundRect(0f, 0f, 15f, 10.5f, 1.6f, 3).map { (a, b) -> V3(-11f + a, 31f + b, 26.56f) }, back, GLASS, Tone.Mid),
            LineMark(listOf(V3(-16f, 27.5f, 26.57f), V3(-11.5f, 34.6f, 26.57f)), back, Tone.Faint),
        ),
    )
    s += drum(V3(4.6f, 31f, 23.5f), V3.X, 3.4f, 2.4f).with(control = Control.Diopter, move = Move.Turn(V3.X, V3(4.6f, 31f, 23.5f), 40f), detail = true, marks = knurl(V3(4.6f, 31f, 23.5f), V3.X, 3.4f, 2.4f, 16))
    s += prism(Frame.alongZ(V3(-17f, -6f, 20f)), roundRect(0f, 0f, 78f, 54f, 4f), 4f, bevel = 0.8f).with(
        control = Control.Monitor, move = Move.Turn(up, V3(-57.5f, -6f, 22f), -82f), detail = true,
        marks = listOf(
            FillMark(roundRect(0f, 0f, 70f, 46f, 2f, 3).map { (a, b) -> V3(-17f + a, -6f + b, 24.03f) }, back, SCREEN_GLASS, Tone.Lo),
            LineMark(listOf(V3(-42f, -27f, 24.04f), V3(-24f, 15.5f, 24.04f)), back, Tone.Faint),
            LineMark(listOf(V3(-36f, -27f, 24.04f), V3(-18f, 15.5f, 24.04f)), back, Tone.Faint),
        ),
    )
    s += drum(V3(-57.5f, -30f, 22f), up, 2.2f, 48f).with(detail = true, control = Control.Monitor, move = Move.Turn(up, V3(-57.5f, -6f, 22f), -82f))
    val z0 = 20f
    s += button(Control.Menu, V3(-47f, 26f, z0), back, 3.6f, label = "MENU", labelSize = 1.3f)
    s += button(Control.Info, V3(16f, 31f, z0), back, 3.4f, label = "info", labelSize = 1.6f)
    s += button(Control.AeLock, V3(26f, 31f, z0), back, 3.4f, label = "AE-L", labelSize = 1.3f)
    s += button(Control.Playback, V3(33f, 14f, z0), back, 3.6f, label = "▶", labelSize = 2.4f)
    s += button(Control.IButton, V3(33f, 4f, z0), back, 3.6f, label = "i", labelSize = 3.2f)
    s += drum(V3(45f, -10f, z0), back, 9.2f, 1.2f, bevel = 0.4f).with(
        control = Control.MultiSelector, move = Move.Press(back, 0.5f), detail = true,
        marks = listOf(
            TextMark("▲", V3(45f, -3.6f, 21.25f), V3.X, up, 1.4f, Tone.Lo), TextMark("▼", V3(45f, -16.4f, 21.25f), V3.X, up, 1.4f, Tone.Lo),
            TextMark("◀", V3(38.6f, -10f, 21.25f), V3.X, up, 1.4f, Tone.Lo), TextMark("▶", V3(51.4f, -10f, 21.25f), V3.X, up, 1.4f, Tone.Lo),
        ),
    )
    s += button(null, V3(45f, -10f, 21.2f), back, 3.8f, height = 1.4f, label = "OK", labelSize = 1.9f)
    s += button(Control.ZoomIn, V3(32f, -24f, z0), back, 3f, label = "+", labelSize = 2.4f)
    s += button(Control.ZoomOut, V3(32f, -32f, z0), back, 3f, label = "−", labelSize = 2.4f)
    s += button(Control.Delete, V3(42f, -29f, z0), back, 3.2f, label = "⌫", labelSize = 2f)

    // — Top plate: mode dial, live view lever, command dial, shutter in its collar, record and ± buttons —
    val dial = V3(27f, 38f, 6f)
    val indexAngle = 228.0
    val letters = DIAL_ORDER.mapIndexed { i, m ->
        val phi = Math.toRadians(indexAngle + (i - DIAL_ORDER.indexOf(mode)) * 45.0)
        val out = V3(sin(phi).toFloat(), 0f, -cos(phi).toFloat())
        val along = V3(cos(phi).toFloat(), 0f, sin(phi).toFloat())
        val green = m == Mode.Auto || m == Mode.NoFlash
        TextMark(if (m == Mode.NoFlash) "⊘" else m.mark, dial + up * 7.05f + out * 9.2f, along, out, if (m.mark.length == 1) 3.4f else 1.5f, Tone.Hi, if (green) 0xFF8FD18A.toInt() else null)
    }
    s += drum(dial, up, 13f, 7f, bevel = 0.8f).with(
        control = Control.ModeDial, move = Move.Turn(up, dial, 45f), detail = true,
        marks = knurl(dial, up, 13f, 7f, 60) + letters + listOf(ringLine(dial + up * 7.04f, up, 5.6f, Tone.Lo, 28, 0.8f)),
    )
    s += drum(V3(14.6f, 38f, 18.6f), up, 0.9f, 0.3f, segments = 10).with(detail = true, edge = Tone.Hi, lift = 1f)
    s += prism(Frame.alongY(V3(42.5f, 38f, 16f)), roundRect(0f, 0f, 4.2f, 8f, 1.4f, 3), 2.4f, bevel = 0.3f).with(
        control = Control.LiveView, move = Move.Turn(up, V3(42.5f, 38f, 12f), -30f), detail = true,
        marks = listOf(TextMark("Lv", V3(42.5f, 40.45f, 16f), V3.Z * -1f, V3.X * -1f, 1.6f, Tone.Mid)),
    )
    val cmd = V3(53f, 38f, 12f)
    s += drum(cmd, up, 8f, 4.5f, bevel = 0.5f).with(control = Control.CommandDial, move = Move.Turn(up, cmd, 18f), detail = true, marks = knurl(cmd, up, 8f, 4.5f, 40))
    val shutter = V3(51f, 38f, -27f)
    s += drum(shutter, up, 9.5f, 2.4f, bevel = 0.4f).with(
        control = Control.Power, move = Move.Turn(up, shutter, 32f), detail = true,
        marks = listOf(TextMark("OFF", V3(39.5f, 38.05f, -14.5f), V3.X, V3.Z * -1f, 1.7f, Tone.Lo), TextMark("ON", V3(46.5f, 38.05f, -13.6f), V3.X, V3.Z * -1f, 1.7f, Tone.Mid)),
    )
    s += prism(Frame.alongY(V3(42.6f, 38f, -21.5f)), roundRect(0f, 0f, 6f, 4.4f, 1.6f, 3), 2.4f, bevel = 0.3f).with(control = Control.Power, move = Move.Turn(up, shutter, 32f), detail = true)
    s += drum(shutter + up * 2.4f, up, 6f, 2.4f, bevel = 0.6f).with(control = Control.Shutter, move = Move.Press(up, 1.4f), lift = 0.55f, detail = true, edge = Tone.Hi)
    s += button(Control.MovieRecord, V3(44f, 38f, -8f), up, 3.4f, label = "●", labelUp = V3.Z * -1f, labelSize = 2.4f, labelColor = RED)
    s += button(Control.ExposureComp, V3(55f, 38f, -5f), up, 3.6f, label = "±", labelUp = V3.Z * -1f, labelSize = 3.2f)
    // Strap eyelets on both sides.
    s += drum(V3(-62f, 30f, 4f), V3.X * -1f, 2.4f, 2.4f).with(detail = true, lift = 0.3f)
    s += drum(V3(62f, 30f, -4f), V3.X, 2.4f, 2.4f).with(detail = true, lift = 0.3f)

    // — Front —
    val bolt = listOf(V3(0.5f, 1.7f, 0f), V3(-0.8f, -0.1f, 0f), V3(0.6f, -0.1f, 0f), V3(-0.5f, -1.7f, 0f))
    s += button(Control.Flash, V3(-52f, 20f, -20f), front, 3.2f, extra = listOf(LineMark(bolt.map { V3(-52f - it.x, 20f + it.y, -20f) }, front, Tone.Mid, 0.9f)))
    s += button(Control.Fn, V3(-52f, 9f, -20f), front, 3.2f, label = "Fn", labelSize = 1.9f)
    s += button(Control.LensRelease, V3(-47.5f, -9f, -20f), front, 4.4f)
    s += button(Control.ReleaseMode, V3(-52f, -25f, -20f), front, 2.8f)
    s += drum(V3(30f, 26f, -20f), front, 3f, 0.6f).with(detail = true, marks = listOf(disc(V3(30f, 26f, -20.63f), front, 2.3f, AMBER)))
    s += prism(Frame.alongMinusZ(V3(-44f, 31f, -20f)), roundRect(0f, 0f, 14f, 4.6f, 0.8f, 2), 0.4f).with(detail = true, edge = Tone.Lo, marks = listOf(TextMark("D5600", V3(-44f, 31f, -20.43f), V3.X * -1f, up, 2.6f, Tone.Hi)))

    // — Lens: mount, base, ribbed zoom ring with its focal lengths, the white line, focus ring, front and glass —
    s += drum(LENS, front, 29f, 3f, bevel = 0.5f).with(lift = 0.7f, edge = Tone.Hi)
    s += drum(LENS + front * 3f, front, 31f, 8f, bevel = 0.8f).with(marks = listOf(TextMark("18  24  35  45  55", LENS + front * 7f + V3(0f, 31.05f, 0f), V3.X, front, 2.2f, Tone.Mid)))
    s += drum(LENS + front * 11f, front, 32.2f, 24f, bevel = 1f).with(
        control = Control.ZoomRing, move = Move.Turn(front, LENS, 28f),
        marks = knurl(LENS + front * 11f, front, 32.2f, 24f, 90, Tone.Lo, 1.6f) + listOf(ringLine(LENS + front * 35.02f, front, 31.2f, Tone.Hi, 64, 1.2f)),
    )
    s += drum(LENS + front * 35f, front, 31f, 9f, bevel = 0.8f).with(control = Control.FocusRing, move = Move.Turn(front, LENS, 22f), marks = knurl(LENS + front * 35f, front, 31f, 9f, 120, Tone.Lo, 1f))
    val face = LENS + front * 50.03f
    s += drum(LENS + front * 44f, front, 30.4f, 6f, bevel = 1f).with(
        marks = listOf(
            disc(face, front, 24f, GLASS, Tone.Mid, 64),
            ringLine(face, front, 20f, Tone.Faint, 56, 0.8f),
            ringLine(face, front, 13f, Tone.Faint, 48, 0.8f),
            LineMark((0..14).map { k -> val a = Math.toRadians(205.0 + k * 4.0); face + V3(-16f * cos(a).toFloat(), 16f * sin(a).toFloat(), -0.01f) }, front, Tone.Hi, 1.4f),
            TextMark("AF-P DX NIKKOR 18–55 mm 1:3.5–5.6G VR", face + V3(0f, 26.6f, 0f), V3.X * -1f, up, 1.5f, Tone.Lo),
        ),
    )

    val topView = -32f to 52f
    val backView = -22f to 22f
    val frontView = 148f to 20f
    val lensView = 112f to 22f
    fun spot(c: Control, at: V3, n: V3, view: Pair<Float, Float>) = Spot(c, at, n.norm(), view.first, view.second)
    val spots = listOf(
        spot(Control.Shutter, shutter + up * 4.9f, up, topView),
        spot(Control.Power, V3(42.6f, 40.5f, -21.5f), up, topView),
        spot(Control.MovieRecord, V3(44f, 39.7f, -8f), up, topView),
        spot(Control.ExposureComp, V3(55f, 39.7f, -5f), up, topView),
        spot(Control.ModeDial, dial + up * 7.2f, up, topView),
        spot(Control.LiveView, V3(42.5f, 40.5f, 16f), up, topView),
        spot(Control.CommandDial, cmd + up * 4.6f, up, -28f to 40f),
        spot(Control.Menu, V3(-47f, 26f, 21.7f), back, backView),
        spot(Control.Viewfinder, V3(-11f, 31f, 26.7f), back, backView),
        spot(Control.Diopter, V3(5.8f, 31f, 27f), back, backView),
        spot(Control.Info, V3(16f, 31f, 21.7f), back, backView),
        spot(Control.AeLock, V3(26f, 31f, 21.7f), back, backView),
        spot(Control.Playback, V3(33f, 14f, 21.7f), back, backView),
        spot(Control.IButton, V3(33f, 4f, 21.7f), back, backView),
        spot(Control.MultiSelector, V3(45f, -10f, 22.7f), back, backView),
        spot(Control.ZoomIn, V3(32f, -24f, 21.7f), back, backView),
        spot(Control.ZoomOut, V3(32f, -32f, 21.7f), back, backView),
        spot(Control.Delete, V3(42f, -29f, 21.7f), back, backView),
        spot(Control.Monitor, V3(-17f, -6f, 24.2f), back, backView),
        spot(Control.Flash, V3(-52f, 20f, -21.7f), front, frontView),
        spot(Control.Fn, V3(-52f, 9f, -21.7f), front, frontView),
        spot(Control.LensRelease, V3(-47.5f, -9f, -21.7f), front, frontView),
        spot(Control.ReleaseMode, V3(-52f, -25f, -21.7f), front, frontView),
        spot(Control.ZoomRing, LENS + front * 23f + V3(-32.4f, 0f, 0f), V3.X * -1f, lensView),
        spot(Control.FocusRing, LENS + front * 39.5f + V3(-31.2f, 0f, 0f), V3.X * -1f, lensView),
    )
    return Rig(s, spots)
}

// ——— Painting ———

/** The figure's inks, from the anatomy kit's dark theme, warmed a touch towards the app's jade-black. */
internal object Inks {
    val shade = intArrayOf(0xFF0E1110.toInt(), 0xFF121514.toInt(), 0xFF151917.toInt(), 0xFF181C1A.toInt(), 0xFF1B1F1D.toInt())
    const val TOP = 0xFF1D2220.toInt()
    const val LIT_TOP = 0xFF29302C.toInt()
    const val LIT_SHADE = 0xFF1F2522.toInt()
    const val FAINT = 0xFF2A302C.toInt()
    const val LO = 0xFF3E4541.toInt()
    const val MID = 0xFF6A726D.toInt()
    const val HI = 0xFFD4DAD5.toInt()
    const val LIT = 0xFFFFFFFF.toInt()
    const val DOT = 0xFFE6EBE7.toInt()

    fun line(t: Tone) = when (t) { Tone.Faint -> FAINT; Tone.Lo -> LO; Tone.Mid -> MID; Tone.Hi -> HI; Tone.Lit -> LIT }
}

/** A part's pose during its demonstration: rotation about a pivot, then a shift. */
private class Pose(val m: FloatArray, val pivot: V3, val t: V3) {
    fun point(p: V3): V3 {
        val q = p - pivot
        return V3(m[0] * q.x + m[1] * q.y + m[2] * q.z, m[3] * q.x + m[4] * q.y + m[5] * q.z, m[6] * q.x + m[7] * q.y + m[8] * q.z) + pivot + t
    }
    fun dir(v: V3) = V3(m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z, m[6] * v.x + m[7] * v.y + m[8] * v.z)
}

private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

private fun rotation(axis: V3, degrees: Float): FloatArray {
    val a = axis.norm()
    val th = Math.toRadians(degrees.toDouble())
    val c = cos(th).toFloat(); val s = sin(th).toFloat(); val t = 1 - c
    return floatArrayOf(
        t * a.x * a.x + c, t * a.x * a.y - s * a.z, t * a.x * a.z + s * a.y,
        t * a.x * a.y + s * a.z, t * a.y * a.y + c, t * a.y * a.z - s * a.x,
        t * a.x * a.z - s * a.y, t * a.y * a.z + s * a.x, t * a.z * a.z + c,
    )
}

/** What the painter needs to know about the moment being drawn. */
internal class Scene(
    val yaw: Float,
    val pitch: Float,
    val staticYaw: Float,
    val selected: Control?,
    val press: Float,
    val turn: Float,
    /** Controls drawn lit, and how strongly (a slow breath for ones still to visit). */
    val lit: Map<Control, Float>,
    /** Degrees the mode dial sits turned from the mode the rig was built at, demo or not (0 = reads that mode). */
    val dial: Float = 0f,
)

/**
 * Paints the rig in isometric (orthographic) projection, far to near. Keeps the screen outline of every control's
 * parts from the last frame for tapping.
 */
internal class Painter(private val rig: Rig, typeface: Typeface, private val density: Float) {
    private val maxVerts = rig.solids.maxOf { it.verts.size / 3 }
    private val px = FloatArray(maxVerts)
    private val py = FloatArray(maxVerts)
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 0.7f; strokeJoin = Paint.Join.ROUND }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 48f }
    private val matrix = Matrix()
    private val hulls = ArrayList<Pair<Control, FloatArray>>()

    private var cy = 1f; private var sy = 0f; private var cp = 1f; private var sp = 0f
    private var ox = 0f; private var oy = 0f; private var k = 1f

    fun setView(cx: Float, cyScreen: Float, scale: Float, yaw: Float, pitch: Float) {
        val yr = Math.toRadians(yaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
        cy = cos(yr).toFloat(); sy = sin(yr).toFloat(); cp = cos(pr).toFloat(); sp = sin(pr).toFloat()
        ox = cx; oy = cyScreen; k = scale
    }

    private fun vx(x: Float, z: Float) = x * cy + z * sy
    private fun vz1(x: Float, z: Float) = -x * sy + z * cy

    /** View-space direction (x right, y up, z towards you). */
    fun view(d: V3): V3 {
        val z1 = vz1(d.x, d.z)
        return V3(vx(d.x, d.z), d.y * cp - z1 * sp, d.y * sp + z1 * cp)
    }

    /** Everything turns about the turntable's axis, so the fixed plinth and the turning platter stay concentric. */
    private val pivot = V3(rig.floor.x, 0f, rig.floor.z)

    fun project(p: V3): V3 { val v = view(p - pivot); return V3(ox + v.x * k, oy - v.y * k, v.z) }

    fun facing(n: V3) = view(n).z

    /** The control whose parts were drawn nearest under a screen point last frame. */
    fun hit(x: Float, y: Float): Control? = hulls.lastOrNull { (_, h) -> inside(h, x, y) }?.first

    private fun pose(s: Solid, scene: Scene): Pose? {
        val m = s.move
        val dial = if (s.control == Control.ModeDial && m is Move.Turn) scene.dial else 0f
        if (s.control == null || s.control != scene.selected) {
            return if (dial != 0f && m is Move.Turn) Pose(rotation(m.axis, dial), m.pivot, V3.ZERO) else null
        }
        return when (m) {
            Move.Still -> null
            is Move.Press -> Pose(IDENTITY, V3.ZERO, m.dir * (-m.depth * scene.press))
            is Move.Turn -> Pose(rotation(m.axis, m.degrees * scene.turn + dial), m.pivot, V3.ZERO)
        }
    }

    fun draw(canvas: Canvas, cx: Float, cyScreen: Float, scale: Float, scene: Scene) {
        hulls.clear()
        val line = 0.42f * density
        // Supports first, lowest first (feet, plinth, platter), then everything else far to near; parts on a surface win ties with it.
        setView(cx, cyScreen, scale, scene.yaw, scene.pitch)
        val order = rig.solids.sortedWith(compareBy<Solid>({ !it.base }, { if (it.base) it.centre.y else 0f }, { depthOf(it, scene) }))
        for (s in order) {
            setView(cx, cyScreen, scale, if (s.static) scene.staticYaw else scene.yaw, scene.pitch)
            paint(canvas, s, pose(s, scene), scene, line)
        }
        setView(cx, cyScreen, scale, scene.yaw, scene.pitch)
    }

    private fun depthOf(s: Solid, scene: Scene): Float {
        setView(ox, oy, k, if (s.static) scene.staticYaw else scene.yaw, scene.pitch)
        val c = pose(s, scene)?.point(s.centre) ?: s.centre
        return view(c - pivot).z + if (s.detail) 3f else 0f
    }

    private fun paint(canvas: Canvas, s: Solid, pose: Pose?, scene: Scene, line: Float) {
        val n = s.verts.size / 3
        for (i in 0 until n) {
            var p = V3(s.verts[i * 3], s.verts[i * 3 + 1], s.verts[i * 3 + 2])
            if (pose != null) p = pose.point(p)
            val v = view(p - pivot)
            px[i] = ox + v.x * k
            py[i] = oy - v.y * k
        }
        val lit = s.control?.let { scene.lit[it] } ?: 0f
        val selected = s.control != null && s.control == scene.selected
        // Faces: tone by facing; a lighter top; lit parts a touch brighter.
        for ((fi, f) in s.faces.withIndex()) {
            val wn = pose?.dir(s.normals[fi]) ?: s.normals[fi]
            val vn = view(wn)
            if (vn.z <= 0.001f) continue
            val base = when {
                wn.y > 0.72f -> if (selected) Inks.LIT_TOP else Inks.TOP
                else -> {
                    // Light from the upper left: faces turned that way are lighter, in four steps on flats, smoothly on curves.
                    val l = (0.5f + 0.5f * (-0.62f * vn.x + 0.78f * vn.z)).coerceIn(0f, 1f)
                    val c = if (s.curved[fi]) lerpInk(l) else Inks.shade[(l * 4.99f).toInt().coerceIn(0, 4)]
                    if (selected) blend(c, Inks.LIT_SHADE, 0.6f) else c
                }
            }
            fill.color = if (s.lift > 0f) blend(base, 0xFF8C948F.toInt(), s.lift * 0.35f) else base
            path.rewind()
            for ((j, idx) in f.withIndex()) if (j == 0) path.moveTo(px[idx], py[idx]) else path.lineTo(px[idx], py[idx])
            path.close()
            canvas.drawPath(path, fill)
        }
        // Outline: the silhouette, then the rims of visible caps.
        val edge = when {
            selected -> Inks.LIT
            lit > 0f -> blend(Inks.line(s.edge), Inks.LIT, lit)
            else -> Inks.line(s.edge)
        }
        stroke.strokeWidth = line * (if (selected || lit > 0f) 1.5f else 1f)
        stroke.color = edge
        val hull = hullOf(n)
        path.rewind()
        for (j in 0 until hull.size / 2) if (j == 0) path.moveTo(hull[0], hull[1]) else path.lineTo(hull[j * 2], hull[j * 2 + 1])
        path.close()
        canvas.drawPath(path, stroke)
        if (s.control != null) hulls += s.control to hull
        for ((fi, f) in s.faces.withIndex()) {
            if (!s.outlined[fi]) continue
            val vn = view(pose?.dir(s.normals[fi]) ?: s.normals[fi])
            if (vn.z <= 0.05f) continue
            stroke.alpha = (255 * ((vn.z - 0.05f) / 0.25f).coerceIn(0f, 1f)).toInt().coerceAtMost(android.graphics.Color.alpha(edge))
            path.rewind()
            for ((j, idx) in f.withIndex()) if (j == 0) path.moveTo(px[idx], py[idx]) else path.lineTo(px[idx], py[idx])
            path.close()
            canvas.drawPath(path, stroke)
        }
        stroke.alpha = 255
        for (m in s.marks) mark(canvas, m, pose, line)
    }

    private fun mark(canvas: Canvas, m: Mark, pose: Pose?, line: Float) {
        val f = facing(pose?.dir(m.normal) ?: m.normal)
        if (f < 0.08f) return
        // Marks fade in as their surface turns to face you, rather than popping.
        val a = ((f - 0.08f) / 0.3f).coerceIn(0f, 1f)
        fun p(v: V3) = project(pose?.point(v) ?: v)
        when (m) {
            is LineMark -> {
                stroke.color = Inks.line(m.tone); stroke.alpha = (255 * a).toInt(); stroke.strokeWidth = line * m.width
                path.rewind()
                m.pts.forEachIndexed { i, v -> val q = p(v); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
                canvas.drawPath(path, stroke)
            }
            is DotMark -> {
                fill.color = Inks.DOT; fill.alpha = (255 * a * m.alpha).toInt()
                val q = p(m.at)
                canvas.drawCircle(q.x, q.y, max(0.6f * density * 0.5f, m.radius * k), fill)
            }
            is FillMark -> {
                fill.color = m.color; fill.alpha = (255 * a).toInt()
                path.rewind()
                m.pts.forEachIndexed { i, v -> val q = p(v); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
                path.close()
                canvas.drawPath(path, fill)
                if (m.edge != null) { stroke.color = Inks.line(m.edge); stroke.alpha = (255 * a).toInt(); stroke.strokeWidth = line; canvas.drawPath(path, stroke) }
            }
            is TextMark -> {
                val w = text.measureText(m.text)
                val fm = text.fontMetrics
                val th = fm.descent - fm.ascent
                val sc = m.height / th
                val right = pose?.dir(m.right) ?: m.right
                val up = pose?.dir(m.up) ?: m.up
                val at = pose?.point(m.at) ?: m.at
                val tl = at - right * (w * sc / 2f) + up * (th * sc / 2f)
                val p0 = project(tl); val p1 = project(tl + right * (w * sc)); val p2 = project(tl - up * (th * sc))
                matrix.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, 0f, th), 0, floatArrayOf(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y), 0, 3)
                text.color = m.color ?: Inks.line(m.tone)
                text.alpha = (255 * a).toInt()
                canvas.save(); canvas.concat(matrix); canvas.drawText(m.text, 0f, -fm.ascent, text); canvas.restore()
            }
        }
        fill.alpha = 255
    }

    /** Convex hull of the projected vertices (monotone chain), as x, y pairs. */
    private fun hullOf(n: Int): FloatArray {
        val idx = (0 until n).sortedWith(compareBy({ px[it] }, { py[it] }))
        fun cross(o: Int, a: Int, b: Int) = (px[a] - px[o]) * (py[b] - py[o]) - (py[a] - py[o]) * (px[b] - px[o])
        val h = IntArray(2 * n)
        var m = 0
        for (i in idx) { while (m >= 2 && cross(h[m - 2], h[m - 1], i) <= 0) m--; h[m++] = i }
        val lower = m + 1
        for (i in idx.reversed()) { while (m >= lower && cross(h[m - 2], h[m - 1], i) <= 0) m--; h[m++] = i }
        val out = FloatArray((m - 1) * 2)
        for (j in 0 until m - 1) { out[j * 2] = px[h[j]]; out[j * 2 + 1] = py[h[j]] }
        return out
    }
}

private fun inside(h: FloatArray, x: Float, y: Float): Boolean {
    val n = h.size / 2
    if (n < 3) return false
    var sign = 0f
    for (i in 0 until n) {
        val j = (i + 1) % n
        val c = (h[j * 2] - h[i * 2]) * (y - h[i * 2 + 1]) - (h[j * 2 + 1] - h[i * 2 + 1]) * (x - h[i * 2])
        if (c != 0f) { if (sign == 0f) sign = c else if (sign * c < 0) return false }
    }
    return true
}

private fun lerpInk(t: Float): Int {
    val f = t * 4f
    val i = f.toInt().coerceIn(0, 3)
    return blend(Inks.shade[i], Inks.shade[i + 1], f - i)
}

private fun blend(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - u) + ((b shr s) and 0xFF) * u).toInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
