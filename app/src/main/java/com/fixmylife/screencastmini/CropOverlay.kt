package com.fixmylife.screencastmini

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Floating crop box the user drags over whatever should appear on the mini screen,
 * plus a small lock button. When locked the box stops eating touches, so Maps
 * can be used normally while the box keeps marking the same region.
 */
class CropOverlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val overlayType =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private var locked = false
    private var shown = false

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private val boxParams = WindowManager.LayoutParams(
        dp(220), dp(220), overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(40)
        y = dp(140)
    }

    private val buttonParams = WindowManager.LayoutParams(
        dp(56), dp(40), overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(8)
        y = dp(90)
    }

    private val box = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setStroke(dp(2), Color.rgb(0, 200, 255))
            setColor(0x11000000)
        }
    }

    private val resizeHandle = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(0, 200, 255))
        }
        layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.END or Gravity.BOTTOM)
    }

    private val lockButton = TextView(context).apply {
        text = "LOCK"
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xCC000000.toInt())
        }
    }

    init {
        box.addView(resizeHandle)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = boxParams.x; startY = boxParams.y
                }
                MotionEvent.ACTION_MOVE -> {
                    boxParams.x = startX + (e.rawX - downX).roundToInt()
                    boxParams.y = startY + (e.rawY - downY).roundToInt()
                    if (shown) wm.updateViewLayout(box, boxParams)
                }
            }
            true
        }

        var rw = 0
        var rh = 0
        resizeHandle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    rw = boxParams.width; rh = boxParams.height
                }
                MotionEvent.ACTION_MOVE -> {
                    boxParams.width = max(dp(80), rw + (e.rawX - downX).roundToInt())
                    boxParams.height = max(dp(80), rh + (e.rawY - downY).roundToInt())
                    if (shown) wm.updateViewLayout(box, boxParams)
                }
            }
            true
        }

        lockButton.setOnClickListener { setLocked(!locked) }
    }

    /** Region of the screen currently framed, in screen pixels. */
    fun rect(): Rect = Rect(
        boxParams.x, boxParams.y,
        boxParams.x + boxParams.width, boxParams.y + boxParams.height
    )

    fun show() {
        if (shown) return
        wm.addView(box, boxParams)
        wm.addView(lockButton, buttonParams)
        shown = true
    }

    fun hide() {
        if (!shown) return
        runCatching { wm.removeView(box) }
        runCatching { wm.removeView(lockButton) }
        shown = false
    }

    private fun setLocked(value: Boolean) {
        locked = value
        lockButton.text = if (locked) "MOVE" else "LOCK"
        boxParams.flags = if (locked) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        resizeHandle.visibility = if (locked) View.GONE else View.VISIBLE
        if (shown) wm.updateViewLayout(box, boxParams)
    }
}
