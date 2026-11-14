package com.maguardian.security.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatButton

/** Reserve the centered icon/text group before TextView constructs its text layout. */
class CenteredIconButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatButton(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val icon = compoundDrawables[0]
        if (icon != null && View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
            val group = paint.measureText(text.toString()) + icon.bounds.width() + compoundDrawablePadding
            val inset = ((View.MeasureSpec.getSize(widthMeasureSpec) - group) / 2).toInt()
                .coerceAtLeast((4 * resources.displayMetrics.density).toInt())
            if (paddingLeft != inset || paddingRight != inset)
                setPadding(inset, paddingTop, inset, paddingBottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
