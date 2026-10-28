package app.nastavnik.overlay

import android.animation.ValueAnimator
import android.graphics.Color
import android.view.animation.LinearInterpolator

/** Фирменные градиенты оверлея и вращение для индикатора «думаю». */
object Rainbow {
    /** Фирменное кольцо кнопки: фиолетово-синий → сиреневый → розовый и обратно. */
    val BRAND = intArrayOf(
        Color.parseColor("#5B4CF0"),
        Color.parseColor("#8E5CF7"),
        Color.parseColor("#E06AB8"),
        Color.parseColor("#8E5CF7"),
        Color.parseColor("#5B4CF0"),
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
