package com.example.markdownreader

import android.content.Context
import android.text.Spannable
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView

/**
 * A TextView that reliably delivers taps to ClickableSpans (links) even when
 * android:textIsSelectable="true" is set.
 *
 * Why this exists: with a selectable TextView, simply assigning
 * `movementMethod = LinkMovementMethod.getInstance()` is *not* reliable —
 * TextView's own touch handling (the "Editor" that manages the text cursor
 * and selection handles, installed because the text is selectable) can
 * intercept and consume touch events for its own gesture detection before a
 * movement method ever gets a look at them. Symptoms vary by device/OS
 * version, but the net effect is the one reported here: tapping a link does
 * nothing.
 *
 * The fix is to check for a link under the finger ourselves, in
 * onTouchEvent(), *before* calling into the superclass at all. If the tap
 * landed on a ClickableSpan we handle it right there and stop; any other tap
 * is passed straight through to the normal selectable-text behavior,
 * completely unaffected. This mirrors exactly what LinkMovementMethod does
 * internally, just running early enough to never get pre-empted.
 */
class LinkAwareTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val content = text
        val layout = layout
        if (content is Spannable &&
            layout != null &&
            (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_DOWN)
        ) {
            var x = event.x.toInt()
            var y = event.y.toInt()
            x -= totalPaddingLeft
            y -= totalPaddingTop
            x += scrollX
            y += scrollY

            val line = layout.getLineForVertical(y)
            val offset = layout.getOffsetForHorizontal(line, x.toFloat())
            val links = content.getSpans(offset, offset, ClickableSpan::class.java)

            if (links.isNotEmpty()) {
                if (event.action == MotionEvent.ACTION_UP) {
                    links[0].onClick(this)
                }
                // Consume both DOWN and UP for the link so the selection
                // Editor never gets a chance to treat this as a text tap.
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
