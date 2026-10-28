package app.nastavnik.overlay

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.nastavnik.ui.ToggleActivity

/**
 * Плитка «Наставник» в шторке быстрых настроек: одно касание включает или выключает кнопку поверх Touchnotes,
 * не открывая приложение.
 */
class MentorTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        // Сервис с кнопкой запускается из видимого окна — так Android разрешает его включить из шторки.
        val intent = Intent(this, ToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = BubbleService.running
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Наставник"
        tile.subtitle = if (on) "Включён" else "Выключен"
        tile.contentDescription = if (on) "Наставник включён" else "Наставник выключен"
        tile.updateTile()
    }

    companion object {
        /** Попросить шторку перерисовать плитку (наставника включили или выключили в другом месте). */
        fun update(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, MentorTileService::class.java)) }
        }
    }
}
