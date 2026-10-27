package app.nastavnik.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager

/** Общие помощники для окон TYPE_APPLICATION_OVERLAY. */
object OverlayWindows {

    /** Окно, которое не забирает фокус и пропускает касания вне себя в Touchnotes. */
    const val PASS_THROUGH_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    fun params(width: Int, height: Int, flags: Int = PASS_THROUGH_FLAGS): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    /** Окно на весь физический экран, включая строку состояния (для выбора области). */
    fun fullScreenParams(): WindowManager.LayoutParams =
        params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    /**
     * Окно в абсолютных координатах экрана (с учётом строки состояния и выреза) —
     * для заметок, которые должны лечь точно на место на снимке.
     */
    fun absParams(x: Int, y: Int, width: Int, height: Int, flags: Int): WindowManager.LayoutParams =
        params(width, height, flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    /** Android 12+ пропускает касания сквозь чужое окно, только если его непрозрачность не больше 0,8. */
    const val MAX_PASS_THROUGH_ALPHA = 0.8f

    @Suppress("DEPRECATION")
    fun screenSize(context: Context): Point {
        val m = DisplayMetrics()
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return Point(m.widthPixels, m.heightPixels)
    }

    fun isLandscape(context: Context): Boolean {
        val s = screenSize(context)
        return if (s.x != s.y) s.x > s.y else context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * Прямоугольник без строки состояния и панели навигации — в координатах полного экрана.
     * На Android 11+ учитываются только видимые сейчас панели: если Touchnotes
     * в полноэкранном режиме, обрезать нечего.
     */
    fun contentRect(context: Context): Rect {
        val size = screenSize(context)
        var top: Int
        var bottom: Int
        var left = 0
        var right = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val wm = context.getSystemService(WindowManager::class.java)
            val bars = wm.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right
        } else {
            top = dimen(context, "status_bar_height")
            bottom = dimen(context, if (size.x > size.y) "navigation_bar_height_landscape" else "navigation_bar_height")
        }
        if (top + bottom >= size.y / 2) { top = 0; bottom = 0 }
        if (left + right >= size.x / 2) { left = 0; right = 0 }
        return Rect(left, top, size.x - right, size.y - bottom)
    }

    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun dimen(context: Context, name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
