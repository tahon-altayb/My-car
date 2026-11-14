package com.example.launcher

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.TextView

/** نص يصغر لحاله لما يكون مربعه صغير (للساعة والسرعة لما يتغير حجم المربع). */
class FitTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextView(context, attrs) {

    private val maxPx = textSize
    private var fitting = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refit()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, after: Int) {
        super.onTextChanged(text, start, before, after)
        refit()
    }

    private fun refit() {
        if (fitting || width <= 0 || height <= 0) return
        fitting = true
        val availW = (width - paddingLeft - paddingRight) * 0.96f
        val availH = (height - paddingTop - paddingBottom).toFloat()
        var size = Math.min(maxPx, availH * 0.82f)
        val probe = paint
        val str = text?.toString() ?: ""
        while (size > 10f) {
            probe.textSize = size
            if (probe.measureText(str) <= availW) break
            size *= 0.92f
        }
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        fitting = false
    }
}
