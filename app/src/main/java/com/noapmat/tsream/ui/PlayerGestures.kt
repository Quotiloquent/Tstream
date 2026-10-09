package com.noapmat.tsream.ui

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Touch handling for the video area:
 *  - single tap: show/hide controls
 *  - double tap: left third = back, right third = forward, middle = play/pause
 *  - vertical swipe on the left half: volume
 *  - press and hold without moving for [HOLD_MS]: temporary fast-forward (released on lift)
 */
class PlayerGestures(private val view: View, private val cb: Callbacks) : View.OnTouchListener {

    interface Callbacks {
        fun onSingleTap()
        fun onDoubleTap(zone: Zone)
        /** +1.0 = a swipe up over the whole height of the view. */
        fun onVolumeScroll(fraction: Float)
        fun onHoldStart()
        fun onHoldEnd()
    }

    enum class Zone { LEFT, CENTER, RIGHT }

    companion object {
        const val HOLD_MS = 1200L
    }

    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var holding = false
    private val holdRunnable = Runnable { holding = true; cb.onHoldStart() }

    private fun zoneOf(x: Float): Zone {
        val w = view.width.toFloat()
        return when {
            x < w / 3f -> Zone.LEFT
            x > 2f * w / 3f -> Zone.RIGHT
            else -> Zone.CENTER
        }
    }

    private val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { cb.onSingleTap(); return true }

        override fun onDoubleTap(e: MotionEvent): Boolean { cb.onDoubleTap(zoneOf(e.x)); return true }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val start = e1 ?: return false
            val vertical = abs(e2.y - start.y) > abs(e2.x - start.x)
            if (vertical && start.x < view.width / 2f) {
                cb.onVolumeScroll(distanceY / view.height) // distanceY > 0 when the finger moves up
                return true
            }
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; holding = false
                v.removeCallbacks(holdRunnable)
                v.postDelayed(holdRunnable, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE ->
                if (!holding && hypot(e.x - downX, e.y - downY) > slop) v.removeCallbacks(holdRunnable)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(holdRunnable)
                if (holding) { holding = false; cb.onHoldEnd() }
            }
        }
        detector.onTouchEvent(e)
        return true
    }
}
