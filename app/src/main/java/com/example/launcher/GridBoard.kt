package com.example.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * لوحة الواجهة الرئيسية: شبكة 12 عمود × 8 صفوف، وكل مربع له مكان وحجم بالخلايا.
 * في وضع التعديل: السحب من وسط المربع يحركه، والسحب من الزاوية السفلية يكبّره ويصغّره.
 */
class GridBoard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    companion object {
        const val COLS = 12
        const val ROWS = 8
    }

    class Widget(val key: String, val view: View, val minW: Int, val minH: Int, val label: String) {
        var cell = Rect()
    }

    val widgets = ArrayList<Widget>()
    private val density = context.resources.displayMetrics.density
    private val gap = 14 * density

    var editMode = false
        set(value) {
            field = value
            active = null
            invalidate()
        }

    /** يستدعى بعد كل تعديل ناجح عشان يُحفظ. */
    var onChanged: (() -> Unit)? = null
    var onRejected: (() -> Unit)? = null

    // حالة السحب
    private var active: Widget? = null
    private var resizing = false
    private var downX = 0f
    private var downY = 0f
    private var startCell = Rect()
    private val ghost = Rect()
    private var ghostValid = true

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 7 * density), 0f)
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16 * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        setWillNotDraw(false)
    }

    fun add(key: String, view: View, minW: Int, minH: Int, label: String) {
        widgets.add(Widget(key, view, minW, minH, label))
    }

    fun find(key: String): Widget? = widgets.firstOrNull { it.key == key }

    private fun cellW(): Float = (width - paddingLeft - paddingRight + gap) / COLS
    private fun cellH(): Float = (height - paddingTop - paddingBottom + gap) / ROWS

    private fun pixels(cell: Rect, out: RectF) {
        val cw = cellW()
        val ch = cellH()
        out.set(
            paddingLeft + cell.left * cw,
            paddingTop + cell.top * ch,
            paddingLeft + cell.right * cw - gap,
            paddingTop + cell.bottom * ch - gap
        )
    }

    private val tmp = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = (w - paddingLeft - paddingRight + gap) / COLS
        val ch = (h - paddingTop - paddingBottom + gap) / ROWS
        for (wd in widgets) {
            if (wd.view.visibility == View.GONE) continue
            val pw = Math.max(0, (wd.cell.width() * cw - gap).toInt())
            val ph = Math.max(0, (wd.cell.height() * ch - gap).toInt())
            wd.view.measure(
                MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (wd in widgets) {
            if (wd.view.visibility == View.GONE) continue
            pixels(wd.cell, tmp)
            wd.view.layout(tmp.left.toInt(), tmp.top.toInt(), tmp.left.toInt() + wd.view.measuredWidth,
                tmp.top.toInt() + wd.view.measuredHeight)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)

    // ---------- التحقق من التداخل ----------

    fun overlaps(cell: Rect, except: Widget?): Boolean {
        for (wd in widgets) {
            if (wd === except || wd.view.visibility == View.GONE) continue
            if (Rect.intersects(cell, wd.cell)) return true
        }
        return false
    }

    /** يدوّر على أول مكان فاضي بنفس الحجم. */
    fun findFreeSpot(widget: Widget): Boolean {
        val w = widget.cell.width()
        val h = widget.cell.height()
        for (y in 0..ROWS - h) {
            for (x in 0..COLS - w) {
                val r = Rect(x, y, x + w, y + h)
                if (!overlaps(r, widget)) {
                    widget.cell = r
                    return true
                }
            }
        }
        return false
    }

    // ---------- وضع التعديل ----------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = editMode

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return false
        val cw = cellW()
        val ch = cellH()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = null
                for (wd in widgets.reversed()) {
                    if (wd.view.visibility == View.GONE) continue
                    pixels(wd.cell, tmp)
                    if (tmp.contains(event.x, event.y)) {
                        active = wd
                        val corner = 46 * density
                        resizing = event.x > tmp.right - corner && event.y > tmp.bottom - corner
                        downX = event.x
                        downY = event.y
                        startCell.set(wd.cell)
                        ghost.set(wd.cell)
                        ghostValid = true
                        wd.view.alpha = 0.45f
                        break
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val wd = active ?: return true
                val dx = Math.round((event.x - downX) / cw)
                val dy = Math.round((event.y - downY) / ch)
                if (resizing) {
                    val w = (startCell.width() + dx).coerceIn(wd.minW, COLS - startCell.left)
                    val h = (startCell.height() + dy).coerceIn(wd.minH, ROWS - startCell.top)
                    ghost.set(startCell.left, startCell.top, startCell.left + w, startCell.top + h)
                } else {
                    val x = (startCell.left + dx).coerceIn(0, COLS - startCell.width())
                    val y = (startCell.top + dy).coerceIn(0, ROWS - startCell.height())
                    ghost.set(x, y, x + startCell.width(), y + startCell.height())
                }
                ghostValid = !overlaps(ghost, wd)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wd = active ?: return true
                wd.view.alpha = 1f
                if (event.actionMasked == MotionEvent.ACTION_UP && ghost != wd.cell) {
                    if (ghostValid) {
                        wd.cell = Rect(ghost)
                        requestLayout()
                        onChanged?.invoke()
                    } else {
                        onRejected?.invoke()
                    }
                }
                active = null
                invalidate()
                return true
            }
        }
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editMode) return
        val accent = context.themeColor(R.attr.lAccent)
        val text = context.themeColor(R.attr.lText)
        borderPaint.color = accent
        handlePaint.color = accent
        labelPaint.color = text
        for (wd in widgets) {
            if (wd.view.visibility == View.GONE) continue
            pixels(wd.cell, tmp)
            canvas.drawRoundRect(tmp, 18 * density, 18 * density, borderPaint)
            canvas.drawCircle(tmp.right - 14 * density, tmp.bottom - 14 * density, 13 * density, handlePaint)
            canvas.drawText(wd.label, tmp.centerX(), tmp.top + 26 * density, labelPaint)
        }
        val wd = active ?: return
        pixels(ghost, tmp)
        ghostPaint.color = if (ghostValid) 0x5522C55E else 0x55EF4444
        canvas.drawRoundRect(tmp, 18 * density, 18 * density, ghostPaint)
    }

    // ---------- الحفظ ----------

    fun serialize(): String = widgets.joinToString(";") {
        it.key + ":" + it.cell.left + "," + it.cell.top + "," + it.cell.width() + "," + it.cell.height()
    }

    /** يطبّق مواقع محفوظة. أي مربع ما له قيمة صحيحة يرجع لمكانه الافتراضي. */
    fun load(saved: String, defaults: Map<String, Rect>) {
        val parsed = HashMap<String, Rect>()
        for (part in saved.split(";")) {
            val kv = part.split(":")
            if (kv.size != 2) continue
            val n = kv[1].split(",").mapNotNull { it.trim().toIntOrNull() }
            if (n.size != 4) continue
            val r = Rect(n[0], n[1], n[0] + n[2], n[1] + n[3])
            if (r.left >= 0 && r.top >= 0 && r.right <= COLS && r.bottom <= ROWS && r.width() > 0 && r.height() > 0) {
                parsed[kv[0]] = r
            }
        }
        for (wd in widgets) {
            wd.cell = Rect(parsed[wd.key] ?: defaults[wd.key] ?: Rect(0, 0, 2, 2))
        }
        requestLayout()
    }
}
