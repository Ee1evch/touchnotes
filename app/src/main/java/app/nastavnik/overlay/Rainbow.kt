package app.nastavnik.overlay

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

/** Вращение для индикатора «думаю» на кнопке. */
object Rainbow {
    /** Бесконечное вращение градиента: угол в градусах передаётся в [onAngle]. */
    fun spinner(durationMs: Long, onAngle: (Float) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { onAngle(it.animatedValue as Float) }
        }
}
