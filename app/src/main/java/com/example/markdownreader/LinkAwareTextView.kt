package com.example.markdownreader

import android.content.Context
import android.text.Spanned
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView

/**
 * A [TextView][android.widget.TextView] that supports both text selection
 * (`android:textIsSelectable="true"`) and clickable spans (Markwon-rendered
 * links) at the same time.
 *
 * ### The bug this works around
 * Once `textIsSelectable` is `true`, Android's TextView installs its own
 * touch handling for building a text selection, and that handling swallows
 * single taps before they ever reach a [ClickableSpan] — even if a
 * `MovementMethod` is set. In practice this means links inside a
 * selectable TextView become completely inert: tapping them does nothing.
 *
 * ### The fix
 * On `ACTION_UP`, before letting the normal selection machinery run, check
 * whether the tap landed on a [ClickableSpan] (the same offset-resolution
 * logic `LinkMovementMethod` itself uses internally). If it did, invoke the
 * span directly and consume the event. Otherwise, fall through to
 * `super.onTouchEvent()` so selection, long-press, etc. keep working exactly
 * as before.
 */
class LinkAwareTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            findClickableSpanAt(event.x, event.y)?.let { span ->
                span.onClick(this)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findClickableSpanAt(x: Float, y: Float): ClickableSpan? {
        val text = text
        if (text !is Spanned) return null
        val layout = layout ?: return null

        // Same coordinate translation LinkMovementMethod uses: view-space tap
        // position -> position within the laid-out text.
        var px = x.toInt() - totalPaddingLeft
        var py = y.toInt() - totalPaddingTop
        px += scrollX
        py += scrollY

        val line = layout.getLineForVertical(py)
        val offset = layout.getOffsetForHorizontal(line, px.toFloat())

        val spans = text.getSpans(offset, offset, ClickableSpan::class.java)
        return spans.firstOrNull()
    }
}
