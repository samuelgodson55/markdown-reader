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
 * taps before they ever reach a [ClickableSpan] — even if a `MovementMethod`
 * is set. In practice this means links inside a selectable TextView become
 * completely inert: tapping them does nothing.
 *
 * A first attempt at fixing this only intercepted `ACTION_UP`: check for a
 * span, and if present, handle it instead of calling `super`. That mostly
 * worked, but `ACTION_DOWN`/`ACTION_MOVE` were still always forwarded to
 * `super`, which arms Android's own long-press-to-select timer regardless of
 * what happens at `ACTION_UP`. A tap held even slightly too long (very easy
 * to do by accident) would fire *both*: the text-selection UI (armed by the
 * down/move stream reaching `super`) and the link click (handled at up) —
 * producing a stray selection popup alongside the link action.
 *
 * ### The fix
 * Decide once, at `ACTION_DOWN`, whether the gesture starts on a
 * [ClickableSpan]. If it does, we own the *entire* gesture ourselves and
 * never forward it to `super` at all, so Android's selection/long-press
 * machinery never gets armed for that touch — only a genuine tap-and-release
 * on the same span fires the click (a drag off the link, or a cancelled
 * gesture, does nothing). If the gesture starts on plain text, every event
 * goes straight to `super`, unmodified, so selection behaves exactly as
 * stock Android.
 */
class LinkAwareTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var trackedSpan: ClickableSpan? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val span = findClickableSpanAt(event.x, event.y)
                trackedSpan = span
                if (span != null) {
                    // Own this gesture completely; do not let super see ACTION_DOWN,
                    // or it will arm long-press/selection handling for it.
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackedSpan != null) {
                    // Still tracking a link gesture: if the finger drags off the
                    // span, abandon the click (but keep owning the gesture so a
                    // stray ACTION_UP elsewhere doesn't leak into super either).
                    if (findClickableSpanAt(event.x, event.y) !== trackedSpan) {
                        trackedSpan = null
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val span = trackedSpan
                if (span != null) {
                    trackedSpan = null
                    if (span === findClickableSpanAt(event.x, event.y)) {
                        span.onClick(this)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (trackedSpan != null) {
                    trackedSpan = null
                    return true
                }
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
