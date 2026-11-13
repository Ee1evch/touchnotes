package app.nastavnik.study

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
/** Отмена уведомлений о ДЗ, поставленных предыдущими версиями. */
object HomeworkReminder {
    fun cancel(context: Context) {
        val pending = PendingIntent.getBroadcast(
            context, 7760, Intent(context, AlarmReceiver::class.java).setAction("app.nastavnik.HOMEWORK"),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        context.getSystemService(NotificationManager::class.java)?.cancel(7761)
    }
}
