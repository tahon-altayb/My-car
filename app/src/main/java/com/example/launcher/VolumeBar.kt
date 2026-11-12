package com.example.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return value.data
}

/** شريط صوت عمودي: تسحب بإصبعك فوق وتحت. */
class VolumeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var max: Int = 15
        set(v) {
            field = if (v < 1) 1 else v
            invalidate()
        }

    var value: Int = 0
        set(v) {
            field = v.coerceIn(0, max)
            invalidate()
        }

    /** يستدعى لما المستخدم يغيّر القيمة بإصبعه. */
    var onChange: ((Int) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.lCardAlt)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.lAccent)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f
        canvas.drawRoundRect(0f, 0f, w, h, r, r, trackPaint)
        val fillTop = h - h * value / max
        if (value > 0) {
            canvas.drawRoundRect(0f, fillTop, w, h, r, r, fillPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (height > 0) {
                    val fraction = (1f - event.y / height).coerceIn(0f, 1f)
                    val newValue = Math.round(fraction * max)
                    if (newValue != value) {
                        value = newValue
                        onChange?.invoke(newValue)
                    }
                }
            }
        }
        return true
    }
}
