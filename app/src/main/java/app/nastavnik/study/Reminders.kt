package app.nastavnik.study

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Получатель оставлен для отмены напоминаний из прежних версий. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Alarms.rescheduleAll(context)
    }
}
