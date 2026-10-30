package com.carlink.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.settings.LaneGuidanceConfig
import com.carlink.ui.settings.LaneIconMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws lane guidance into the turn icon GM's cluster and HUD already show (design "Lane layouts
 * inside the HUD turn icon", 2026-10-21). With Android Auto phones GM shows lanes in that same spot,
 * because Google Maps draws them into its turn image; this does the same for CarPlay routes.
 *
 * - [LaneIconMode.ARROW]: the turn arrow shrunk into the top of the square, the lane row beneath.
 * - [LaneIconMode.LANES]: the whole square is lanes; the lanes to be in are lit on a teal band.
 * - [lanesImage]: a separate 6:1 lane strip for `Step.setLanesImage`, which GM places itself.
 *
 * Turn icons are 512 px, like [com.carlink.navigation.compose.ComposedIconStore]'s icons, on
 * transparent: the lanes to be in in [LaneGuidanceConfig.laneColor] (white by default), the rest grey.
 * The last result is kept, so a step's distance ticks reuse one bitmap.
 */
internal object LaneIconComposer {
    private const val SIZE = 512

    // The lane strip for `Step.setLanesImage`. GM fits it into its own lane slot, at most 74 dp tall
    // (the library's 500 x 74 dp box; the lane strip card found 6:1 the widest shape that keeps the full
    // height, truck 2026-10-22). Strips sized to their lanes (v19-v21) left most of that slot empty and
    // the arrows squeezed together, so the strip is always 6:1, split evenly between the lanes, with each
    // arrow centered in its lane and widened to fill it: turns reach sideways and the stroke
    // and head grow with the lane. The caps reach the top and bottom edges. A turn reaches sideways at
    // most [STRIP_MAX_REACH], three quarters of the height, so it reads as a turn arrow rather than a long
    // arm across the lane (up to 256 px in v22-v29; "drawn very far to the left/right", 2026-10-23).
    private const val STRIP_WIDTH = 960
    private const val STRIP_HEIGHT = 160
    private const val STRIP_FILL = 0.86f
    private const val STRIP_STROKE_PER_LANE = 0.12f
    private const val STRIP_MIN_STROKE = 20f
    private const val STRIP_MAX_STROKE = 34f
    private const val STRIP_HEAD_PER_LANE = 0.2f
    private const val STRIP_MIN_HEAD = 36f
    private const val STRIP_MAX_HEAD = 56f
    private const val STRIP_MAX_REACH = 120f

    /** A slight turn's tip sits at most this far sideways per pixel it rises: about 35° off vertical. */
    private const val SLIGHT_LEAN = 0.7f

    /** Where a slight turn bends, as a share of the arrow's height from the top; lower in the strip, for its big heads. */
    private const val SLIGHT_BEND = 0.45f
    private const val STRIP_SLIGHT_BEND = 0.62f

    private val LANE_OFF = Color.rgb(0x70, 0x78, 0x7A)
    private val DIVIDER = Color.rgb(0x4A, 0x52, 0x54)
    private val BAND = Color.rgb(0x14, 0x34, 0x3B)

    // ARROW layout: the arrow's box at the top, the lane row under it.
    private val ARROW_BOX = RectF(106f, 8f, 406f, 308f)
    private val ARROW_LANES = RectF(24f, 336f, 488f, 496f)
    private val ALL_LANES = RectF(16f, 40f, 496f, 472f)

    private const val MAX_STROKE = 18f
    private const val MIN_STROKE = 6f
    private const val STROKE_PER_LANE = 0.12f
    private const val GLYPH_HEIGHT_PER_LANE = 2.4f
    private const val HEAD_PER_LANE = 0.15f
    private const val MIN_HEAD = 8f
    private const val TURN_REACH = 0.3f
    private const val HEAD_SPREAD_DEG = 40.0

    /** How far a head's barbs spread sideways (and a sideways head's up and down), per pixel of head. */
    private val BARB_SPAN = sin(Math.toRadians(HEAD_SPREAD_DEG)).toFloat()
    private const val DIVIDER_WIDTH = 4f
    private val DIVIDER_DASH = floatArrayOf(14f, 12f)
    private const val BAND_INSET = 4f
    private const val BAND_RADIUS = 16f

    private const val STRAIGHT_MAX = 10
    private const val SLIGHT_MAX = 45
    private const val U_TURN_MIN = 175

    /** A U-turn's way back down, from the middle of its curve to the tip, in heads. */
    private const val U_TURN_LEG = 1.1f

    /** Where a U-turn's tip sits below the middle of its curve at least, as a share of the arrow's height. */
    private const val U_TURN_DROP = 0.3f

    private data class Key(
        val base: Maneuver,
        val lanes: List<LaneHint>,
        val mode: LaneIconMode,
        val color: Int,
    )

    /**
     * Where one lane arrow is drawn: the stem at [x] up through [rows] (top..bottom), turns reaching [reach]
     * sideways and slight turns [slight] (bending [bend] of the way down), with an arrowhead [head] long.
     */
    private data class GlyphBox(
        val x: Float,
        val rows: ClosedFloatingPointRange<Float>,
        val reach: Float,
        val slight: Float,
        val head: Float,
        val bend: Float = SLIGHT_BEND,
    ) {
        /**
         * A U-turn's radius: one even half circle across [reach], no taller than leaves the way back
         * down [U_TURN_LEG] heads long, so the head sits low and clear of the curve.
         */
        val uRadius: Float
            get() = min(reach / 2, rows.endInclusive - rows.start - head * U_TURN_LEG).coerceAtLeast(1f)
    }

    private val lock = Any()
    private var lastKey: Key? = null
    private var lastManeuver: Maneuver? = null
    private var lastStripKey: Pair<List<LaneHint>, Int>? = null
    private var lastStrip: CarIcon? = null

    /**
     * [base] with lanes drawn into its icon per [LaneGuidanceConfig.icon], or [base] itself when that
     * is off or there are no [lanes]. Any failure falls back to [base]; the turn icon must never go
     * missing.
     */
    fun maneuver(
        base: Maneuver,
        lanes: List<LaneHint>,
        cfg: LaneGuidanceConfig,
        context: Context,
    ): Maneuver {
        if (cfg.icon == LaneIconMode.OFF || lanes.isEmpty()) return base
        val key = Key(base, lanes, cfg.icon, cfg.laneArgb)
        synchronized(lock) {
            if (key == lastKey && lastKey?.base === base) lastManeuver?.let { return it }
        }
        val built =
            runCatching { base.withIcon(drawTurnIcon(base, lanes, cfg, context)) }
                .onFailure {
                    logWarn("[LANES] could not draw the lane icon, keeping the plain one: ${it.message}", tag = Logger.Tags.NAVI)
                }.getOrNull() ?: return base
        logInfo("[LANES] turn icon: ${cfg.icon.label}, ${describe(lanes)}", tag = Logger.Tags.NAVI)
        synchronized(lock) {
            lastKey = key
            lastManeuver = built
        }
        return built
    }

    /** The lanes alone, 6:1 and split evenly between them, for `Step.setLanesImage`; lanes to be in drawn in [color]. */
    fun lanesImage(
        lanes: List<LaneHint>,
        color: Int,
    ): CarIcon {
        val key = lanes to color
        synchronized(lock) {
            if (key == lastStripKey) lastStrip?.let { return it }
        }
        val bmp = Bitmap.createBitmap(STRIP_WIDTH, STRIP_HEIGHT, Bitmap.Config.ARGB_8888)
        drawStrip(Canvas(bmp), lanes, color)
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
        synchronized(lock) {
            lastStripKey = key
            lastStrip = icon
        }
        return icon
    }

    /** "4 lanes: 0 0 90*(+0) 90*" for the trail; * marks a lane to be in, (+…) its other directions. */
    fun describe(lanes: List<LaneHint>): String =
        "${lanes.size} lanes: " +
            lanes.joinToString(" ") { lane ->
                lane.angle.toString() + (if (lane.recommended) "*" else "") + if (lane.others.isEmpty()) "" else lane.others.joinToString(",", "(+", ")")
            }

    private fun drawTurnIcon(
        base: Maneuver,
        lanes: List<LaneHint>,
        cfg: LaneGuidanceConfig,
        context: Context,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (cfg.icon == LaneIconMode.ARROW) {
            drawBaseIcon(canvas, base, context)
            drawLanes(canvas, lanes, ARROW_LANES, band = false, color = cfg.laneArgb)
        } else {
            drawLanes(canvas, lanes, ALL_LANES, band = true, color = cfg.laneArgb)
        }
        return bmp
    }

    /** The original turn icon (composed bitmap or static vector), fitted into [ARROW_BOX]. */
    private fun drawBaseIcon(
        canvas: Canvas,
        base: Maneuver,
        context: Context,
    ) {
        val drawable = base.icon?.icon?.loadDrawable(context) ?: return
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: SIZE
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: SIZE
        val scale = min(ARROW_BOX.width() / w, ARROW_BOX.height() / h)
        val dw = w * scale
        val dh = h * scale
        val left = ARROW_BOX.centerX() - dw / 2
        val top = ARROW_BOX.centerY() - dh / 2
        drawable.setBounds(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt())
        drawable.draw(canvas)
    }

    /**
     * A row of lane arrows inside [area] for the turn icon: dashed dividers, the lanes to be in lit in
     * [color], the rest grey.
     */
    private fun drawLanes(
        canvas: Canvas,
        lanes: List<LaneHint>,
        area: RectF,
        band: Boolean,
        color: Int,
    ) {
        if (lanes.isEmpty()) return
        val cw = area.width() / lanes.size
        val stroke = (cw * STROKE_PER_LANE).coerceIn(MIN_STROKE, MAX_STROKE)
        val glyphH = min(area.height() - 2 * stroke, cw * GLYPH_HEIGHT_PER_LANE)
        val head = max(MIN_HEAD, cw * HEAD_PER_LANE)
        val glyphTop = area.centerY() - glyphH / 2
        if (band) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = BAND }
            lanes.forEachIndexed { i, lane ->
                if (!lane.recommended) return@forEachIndexed
                val x = area.left + cw * i
                canvas.drawRoundRect(RectF(x + BAND_INSET, area.top, x + cw - BAND_INSET, area.bottom), BAND_RADIUS, BAND_RADIUS, fill)
            }
        }
        drawDividers(canvas, area, lanes.size)
        val reach = cw * TURN_REACH
        lanes.forEachIndexed { i, lane ->
            val box = GlyphBox(area.left + cw * (i + 0.5f), glyphTop..glyphTop + glyphH, reach, reach, head)
            drawLane(canvas, lane, box, stroke, color)
        }
    }

    /** The 6:1 lane strip: the full width split evenly between [lanes], each arrow filling its lane. */
    private fun drawStrip(
        canvas: Canvas,
        lanes: List<LaneHint>,
        color: Int,
    ) {
        if (lanes.isEmpty()) return
        val area = RectF(0f, 0f, STRIP_WIDTH.toFloat(), STRIP_HEIGHT.toFloat())
        val cw = area.width() / lanes.size
        val stroke = (cw * STRIP_STROKE_PER_LANE).coerceIn(STRIP_MIN_STROKE, STRIP_MAX_STROKE)
        val head = (cw * STRIP_HEAD_PER_LANE).coerceIn(STRIP_MIN_HEAD, STRIP_MAX_HEAD)
        drawDividers(canvas, area, lanes.size)
        lanes.forEachIndexed { i, lane ->
            drawLane(canvas, lane, stripBox(lane, area.left + cw * (i + 0.5f), cw, stroke, head), stroke, color)
        }
    }

    /**
     * Where [lane]'s arrow goes in a strip lane [cw] wide centered on [cx]: turns reach as far as
     * [STRIP_FILL] of the lane allows (half each way if the lane turns both ways, less a straight
     * arrow's head if it also goes straight, and a U-turn's head, which spreads past its tip), and
     * the stem moves so the whole arrow is centered.
     */
    private fun stripBox(
        lane: LaneHint,
        cx: Float,
        cw: Float,
        stroke: Float,
        head: Float,
    ): GlyphBox {
        val dirs = listOf(lane.angle) + lane.others
        val span = head * BARB_SPAN
        val barb = if (dirs.any { abs(it) <= STRAIGHT_MAX }) span else 0f
        val tipBarb = if (dirs.any { abs(it) >= U_TURN_MIN }) span else 0f
        val bothWays = dirs.any { it < -STRAIGHT_MAX } && dirs.any { it > STRAIGHT_MAX }
        val room = cw * STRIP_FILL - stroke
        val reach = (if (bothWays) room / 2 - tipBarb else room - barb - tipBarb).coerceIn(stroke, STRIP_MAX_REACH)
        val top = stroke / 2
        val bottom = STRIP_HEIGHT - stroke / 2
        val slight = min(reach, (bottom - top) * STRIP_SLIGHT_BEND * SLIGHT_LEAN)
        val box = GlyphBox(cx, top..bottom, reach, slight, head, STRIP_SLIGHT_BEND)
        // How far sideways each direction's arrow reaches from the stem, signed.
        val ends =
            dirs.map { a ->
                val s = if (a < 0) -1f else 1f
                when {
                    abs(a) <= STRAIGHT_MAX -> 0f
                    abs(a) <= SLIGHT_MAX -> s * slight
                    abs(a) < U_TURN_MIN -> s * reach
                    else -> s * (2 * box.uRadius + span)
                }
            }
        val lo = min(-barb, ends.min())
        val hi = max(barb, ends.max())
        return box.copy(x = cx - (lo + hi) / 2)
    }

    private fun drawDividers(
        canvas: Canvas,
        area: RectF,
        n: Int,
    ) {
        val divider =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = DIVIDER
                style = Paint.Style.STROKE
                strokeWidth = DIVIDER_WIDTH
                pathEffect = DashPathEffect(DIVIDER_DASH, 0f)
            }
        val cw = area.width() / n
        for (i in 1 until n) {
            val x = area.left + cw * i
            canvas.drawLine(x, area.top, x, area.bottom, divider)
        }
    }

    /**
     * One lane's arrows: its other directions grey under its main one, so a straight-or-right lane
     * taken right shows both arrows with the right one lit in [color].
     */
    private fun drawLane(
        canvas: Canvas,
        lane: LaneHint,
        box: GlyphBox,
        stroke: Float,
        color: Int,
    ) {
        val pen =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = LANE_OFF
            }
        lane.others.forEach { canvas.drawPath(glyph(box, it), pen) }
        pen.color = if (lane.recommended) color else LANE_OFF
        canvas.drawPath(glyph(box, lane.angle), pen)
    }

    /** One lane arrow for an Apple lane angle in [b]: 0 straight, +90 right, -90 left, ±180 back. */
    private fun glyph(
        b: GlyphBox,
        angle: Int,
    ): Path {
        val top = b.rows.start
        val h = b.rows.endInclusive - top
        val s = if (angle < 0) -1f else 1f
        val a = abs(angle)
        val p = Path()
        p.moveTo(b.x, b.rows.endInclusive)
        when {
            a <= STRAIGHT_MAX -> {
                p.lineTo(b.x, top)
                arrowHead(p, PointF(b.x, top), PointF(0f, -1f), b.head)
            }
            a <= SLIGHT_MAX -> {
                val midY = top + h * b.bend
                val tipX = b.x + s * b.slight
                p.lineTo(b.x, midY)
                p.lineTo(tipX, top)
                arrowHead(p, PointF(tipX, top), PointF(tipX - b.x, top - midY), b.head)
            }
            a < U_TURN_MIN -> {
                // Low enough that a big head's barbs stay inside the top edge.
                val endY = top + max(h * 0.2f, b.head * BARB_SPAN)
                val tipX = b.x + s * b.reach
                p.lineTo(b.x, top + h * 0.5f)
                p.quadTo(b.x, endY, tipX, endY)
                arrowHead(p, PointF(tipX, endY), PointF(s, 0f), b.head)
            }
            else -> {
                // Up the stem, over one even half circle touching the top, and back down to a head
                // low in the lane (at the bottom when the lane is short, as in the strip).
                val r = b.uRadius
                val cy = top + r
                val tipX = b.x + s * 2 * r
                val tipY = min(b.rows.endInclusive, cy + max(b.head * U_TURN_LEG, h * U_TURN_DROP))
                p.lineTo(b.x, cy)
                p.arcTo(RectF(b.x + s * r - r, top, b.x + s * r + r, top + 2 * r), if (s > 0) 180f else 0f, s * 180f, false)
                p.lineTo(tipX, tipY)
                arrowHead(p, PointF(tipX, tipY), PointF(0f, 1f), b.head)
            }
        }
        return p
    }

    /** Two strokes back from [tip] at ±[HEAD_SPREAD_DEG] around the direction [dir] points. */
    private fun arrowHead(
        p: Path,
        tip: PointF,
        dir: PointF,
        size: Float,
    ) {
        val x = tip.x
        val y = tip.y
        val len = hypot(dir.x, dir.y).takeIf { it > 0f } ?: return
        val bx = -dir.x / len
        val by = -dir.y / len
        val rad = Math.toRadians(HEAD_SPREAD_DEG)
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        p.moveTo(x + (bx * c - by * sn) * size, y + (bx * sn + by * c) * size)
        p.lineTo(x, y)
        p.lineTo(x + (bx * c + by * sn) * size, y + (-bx * sn + by * c) * size)
    }

    /** A copy of this maneuver with a bitmap icon; the type and roundabout fields are kept. */
    private fun Maneuver.withIcon(bitmap: Bitmap): Maneuver {
        val b = Maneuver.Builder(type)
        if (roundaboutExitNumber > 0) b.setRoundaboutExitNumber(roundaboutExitNumber)
        if (roundaboutExitAngle > 0) b.setRoundaboutExitAngle(roundaboutExitAngle)
        b.setIcon(CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build())
        return b.build()
    }
}
