package com.example.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * خلفية زجاجية: ترسم الجزء اللي خلف المربع من صورة الخلفية بعد تغبيشها،
 * وفوقها طبقة شفافة وحد لامع، فيبان كأنه زجاج مثل الآيفون.
 */
class GlassDrawable(
    private val host: View,
    private val radius: Float,
    private val fill: Int,
    private val stroke: Int,
    private val strokeWidth: Float
) : Drawable() {

    companion object {
        /** صورة الخلفية بعد التغبيش (صغيرة، وتتكبر وقت الرسم فتنعم أكثر). */
        var blurred: Bitmap? = null
        /** الشاشة كاملة: نحسب منها مكان كل مربع. */
        var root: View? = null

        /** تغبيش سريع: نصغّر الصورة ثم نمرر عليها تمويه صندوقي ثلاث مرات. */
        fun blur(source: Bitmap): Bitmap {
            val w = 192
            val h = Math.max(1, source.height * w / Math.max(1, source.width))
            val small = Bitmap.createScaledBitmap(source, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            val tmp = IntArray(w * h)
            repeat(3) {
                boxPass(px, tmp, w, h, 6, true)
                boxPass(tmp, px, w, h, 6, false)
            }
            small.setPixels(px, 0, w, 0, 0, w, h)
            return small
        }

        private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            for (line in 0 until lines) {
                var sa = 0; var sr = 0; var sg = 0; var sb = 0
                fun at(i: Int): Int {
                    val c = i.coerceIn(0, len - 1)
                    return if (horizontal) src[line * w + c] else src[c * w + line]
                }
                for (i in -r..r) {
                    val p = at(i)
                    sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
                }
                val n = 2 * r + 1
                for (i in 0 until len) {
                    val out = ((sa / n) shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                    if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                    val add = at(i + r + 1)
                    val rem = at(i - r)
                    sa += (add ushr 24) - (rem ushr 24)
                    sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                    sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                    sb += (add and 255) - (rem and 255)
                }
            }
        }
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke
        style = Paint.Style.STROKE
        this.strokeWidth = this@GlassDrawable.strokeWidth
    }
    private val path = Path()
    private val rect = RectF()
    private val matrix = Matrix()
    private val hostLoc = IntArray(2)
    private val rootLoc = IntArray(2)

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val image = blurred
        val base = root
        if (image != null && base != null && base.width > 0) {
            canvas.save()
            canvas.clipPath(path)
            host.getLocationInWindow(hostLoc)
            base.getLocationInWindow(rootLoc)
            // نفس قص الصورة اللي في الخلفية (centerCrop على مقاس الشاشة)
            val scale = Math.max(base.width.toFloat() / image.width, base.height.toFloat() / image.height)
            val dx = (base.width - image.width * scale) / 2f
            val dy = (base.height - image.height * scale) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx - (hostLoc[0] - rootLoc[0]), dy - (hostLoc[1] - rootLoc[1]))
            canvas.drawBitmap(image, matrix, bitmapPaint)
            canvas.restore()
        }
        canvas.drawPath(path, fillPaint)
        val inset = strokePaint.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
