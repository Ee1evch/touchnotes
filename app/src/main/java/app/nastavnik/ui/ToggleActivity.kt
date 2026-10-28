package app.nastavnik.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.nastavnik.overlay.BubbleService

/** Невидимое окно для плитки в шторке: включает или выключает наставника и сразу закрывается. */
class ToggleActivity : ComponentActivity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        done = savedInstanceState != null
    }

    override fun onResume() {
        super.onResume()
        if (done) return
        done = true
        when {
            // Без разрешения «поверх окон» кнопку не показать — открываем приложение, там объяснят.
            !Permissions.overlay(this) -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            BubbleService.running -> BubbleService.stop(this)
            else -> BubbleService.start(this)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
