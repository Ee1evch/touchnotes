package app.nastavnik.overlay

import android.animation.ValueAnimator
import android.graphics.Color
import android.view.animation.LinearInterpolator

/** Радуга Google: синий → красный → жёлтый → зелёный → снова синий (замкнутый круг для SweepGradient). */
object Rainbow {
    val COLORS = intArrayOf(
        Color.parseColor("#4285F4"),
        Color.parseColor("#9B72CB"),
        Color.parseColor("#EA4335"),
        Color.parseColor("#FBBC04"),
        Color.parseColor("#34A853"),
        Color.parseColor("#4285F4"),
    )

    /** Бесконечное вращение градиента: угол в градусах передаётся в [onAngle]. */
    fun spinner(durationMs: Long, onAngle: (Float) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { onAngle(it.animatedValue as Float) }
        }
}
