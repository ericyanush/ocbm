package com.carlink.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import com.carlink.logging.Logger
import com.carlink.logging.logWarn
import com.carlink.ui.settings.AlertStyle

/**
 * The turn alert's two frames for one step's icon, both drawn here from the same raster so they
 * line up exactly (the flash test sent the step's own icon as the normal frame and a bitmap as the
 * colored one, which is not guaranteed to match).
 *
 * The colored frame keeps a white rim [RIM_PX] wide inside the glyph's edge and colors the core;
 * [AlertStyle.OUTLINE] also colors a ring [OUTLINE_PX] wide outside the edge. Only the white glyph
 * counts: grey junction parts of a roundabout keep their color and get no ring. Sizes are px at
 * [SIZE], the size the composed icons are drawn at, so about a fifth of that on the cluster.
 *
 * Frames are cached by the icon object, which [ManeuverMapper] keeps for the whole step.
 */
internal object TurnAlertFrames {
    class Frames(
        val normal: CarIcon,
        val colored: CarIcon,
    )

    private class Entry(
        val src: IconCompat,
        val color: Int,
        val style: AlertStyle,
        val frames: Frames,
    )

    private const val SIZE = 512
    private const val RIM_PX = 14f
    private const val OUTLINE_PX = 12f
    private const val EDGE_PX = 1.5f
    private const val WHITE_MIN = 0xE0
    private const val ALPHA_MIN = 0x80

    /** Chamfer distance weights, straight and diagonal: about 3 per pixel. */
    private const val STEP = 3
    private const val DIAG = 4
    private const val MAX_CACHED = 8

    private val cache = ArrayDeque<Entry>()

    fun frames(
        icon: CarIcon,
        color: Int,
        style: AlertStyle,
        context: Context,
    ): Frames? {
        val src = icon.icon ?: return null
        synchronized(cache) {
            cache.firstOrNull { it.src === src && it.color == color && it.style == style }?.let { return it.frames }
        }
        val frames =
            runCatching { draw(src, color, style, context) }.getOrElse {
                logWarn("[TURN_ALERT] could not draw the alert frames: ${it.message}", tag = Logger.Tags.NAVI)
                null
            } ?: return null
        synchronized(cache) {
            cache.addLast(Entry(src, color, style, frames))
            while (cache.size > MAX_CACHED) cache.removeFirst()
        }
        return frames
    }

    private fun draw(
        src: IconCompat,
        color: Int,
        style: AlertStyle,
        context: Context,
    ): Frames? {
        val normal = rasterize(src, context) ?: return null
        val colored = normal.copy(Bitmap.Config.ARGB_8888, true)
        paint(colored, color, style)
        return Frames(
            normal = CarIcon.Builder(IconCompat.createWithBitmap(normal)).build(),
            colored = CarIcon.Builder(IconCompat.createWithBitmap(colored)).build(),
        )
    }

    /** [src] at [SIZE], its longer side filling the bitmap as the composed icons and AVDs are drawn. */
    private fun rasterize(
        src: IconCompat,
        context: Context,
    ): Bitmap? {
        val d = src.loadDrawable(context) ?: return null
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: SIZE
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: SIZE
        val scale = SIZE.toFloat() / maxOf(iw, ih)
        val w = (iw * scale).toInt()
        val h = (ih * scale).toInt()
        val left = (SIZE - w) / 2
        val top = (SIZE - h) / 2
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        d.setBounds(left, top, left + w, top + h)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun paint(
        bmp: Bitmap,
        color: Int,
        style: AlertStyle,
    ) {
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val glyph =
            BooleanArray(px.size) { i ->
                val p = px[i]
                Color.alpha(p) >= ALPHA_MIN && Color.red(p) >= WHITE_MIN && Color.green(p) >= WHITE_MIN && Color.blue(p) >= WHITE_MIN
            }
        val inside = distances(glyph, inside = true)
        for (i in px.indices) {
            if (!glyph[i]) continue
            val t = ((inside[i] / STEP.toFloat() - RIM_PX) / EDGE_PX).coerceIn(0f, 1f)
            if (t > 0f) px[i] = blend(px[i], color, t)
        }
        if (style == AlertStyle.OUTLINE) {
            val outside = distances(glyph, inside = false)
            for (i in px.indices) {
                if (glyph[i] || Color.alpha(px[i]) >= ALPHA_MIN) continue
                val cover = ((OUTLINE_PX - outside[i] / STEP.toFloat()) / EDGE_PX + 1f).coerceIn(0f, 1f)
                if (cover > 0f) {
                    val a = maxOf(Color.alpha(px[i]), (cover * 255).toInt())
                    px[i] = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
                }
            }
        }
        bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
    }

    private fun blend(
        p: Int,
        color: Int,
        t: Float,
    ): Int =
        Color.argb(
            Color.alpha(p),
            (Color.red(p) + (Color.red(color) - Color.red(p)) * t).toInt(),
            (Color.green(p) + (Color.green(color) - Color.green(p)) * t).toInt(),
            (Color.blue(p) + (Color.blue(color) - Color.blue(p)) * t).toInt(),
        )

    /**
     * Two-pass chamfer distance (x[STEP] per pixel) from each pixel where [mask] equals [inside] to
     * the nearest pixel where it does not; 0 elsewhere.
     */
    private fun distances(
        mask: BooleanArray,
        inside: Boolean,
    ): IntArray {
        val far = Int.MAX_VALUE / 2
        val d = IntArray(mask.size) { if (mask[it] == inside) far else 0 }
        for (y in 0 until SIZE) for (x in 0 until SIZE) forward(d, x, y)
        for (y in SIZE - 1 downTo 0) for (x in SIZE - 1 downTo 0) backward(d, x, y)
        return d
    }

    /** First chamfer pass: from the left and above. */
    private fun forward(
        d: IntArray,
        x: Int,
        y: Int,
    ) {
        val s = SIZE
        val i = y * s + x
        if (d[i] == 0) return
        var v = d[i]
        if (x > 0) v = minOf(v, d[i - 1] + STEP)
        if (y > 0) {
            v = minOf(v, d[i - s] + STEP)
            if (x > 0) v = minOf(v, d[i - s - 1] + DIAG)
            if (x < s - 1) v = minOf(v, d[i - s + 1] + DIAG)
        }
        d[i] = v
    }

    /** Second chamfer pass: from the right and below. */
    private fun backward(
        d: IntArray,
        x: Int,
        y: Int,
    ) {
        val s = SIZE
        val i = y * s + x
        if (d[i] == 0) return
        var v = d[i]
        if (x < s - 1) v = minOf(v, d[i + 1] + STEP)
        if (y < s - 1) {
            v = minOf(v, d[i + s] + STEP)
            if (x < s - 1) v = minOf(v, d[i + s + 1] + DIAG)
            if (x > 0) v = minOf(v, d[i + s - 1] + DIAG)
        }
        d[i] = v
    }
}
