package app.nastavnik.study

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.ui.SirenActivity
import java.time.LocalDateTime

/**
 * Звук сирены: «wake up» один раз, потом сигнал по кругу, на потоке будильника. Громкость будильника каждую
 * секунду возвращается вверх (первый раз за вечер — 70 %, дальше — максимум): убавить нельзя ни кнопками
 * (их глушит служба защиты), ни ползунком. После — прежняя громкость.
 */
class SirenService : Service() {
    companion object {
        @Volatile var ringing = false
            private set
        const val NOTIFY = 7741
    }

    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var saved = -1
    private var level = 1f
    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification()
        runCatching { startForeground(NOTIFY, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            .onFailure { runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFY, n) } }
        if (!ringing) {
            ringing = true
            val s = SirenPrefs.of(this)
            val day = StudyLockRules.sessionDate(LocalDateTime.now()).toString()
            if (s.ringsDay != day) { s.ringsDay = day; s.rings = 0 }
            s.rings = s.rings + 1
            level = if (s.rings <= 1) 0.7f else 1f
            wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nastavnik:siren")
                ?.apply { setReferenceCounted(false); acquire(30 * 60_000L) }
            play(R.raw.siren_wake_up, loop = false) { play(R.raw.siren_alarm, loop = true) {} }
            main.post(holdVolume)
            openScreen()
        }
        return START_STICKY
    }

    private fun play(res: Int, loop: Boolean, next: () -> Unit) {
        runCatching { player?.release() }
        player = runCatching {
            val afd = resources.openRawResourceFd(res)
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = loop
                setOnCompletionListener { if (!loop && ringing) next() }
                prepare()
                start()
            }
        }.getOrNull()
    }

    /** Раз в секунду: громкость будильника не ниже нужной. */
    private val holdVolume = object : Runnable {
        override fun run() {
            if (!ringing) return
            runCatching {
                val audio = getSystemService(AudioManager::class.java)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val need = (max * level).toInt().coerceIn(1, max)
                val cur = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                if (saved < 0) saved = cur
                if (cur < need) audio.setStreamVolume(AudioManager.STREAM_ALARM, need, 0)
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, NOTIFY, Intent(this, SirenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NastavnikApp.CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Задача дня не решена")
            .setContentText("Реши мини-задачу — 5 минут тишины на основную")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun openScreen() {
        if (!Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(Intent(this, SirenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        }
    }

    override fun onDestroy() {
        ringing = false
        main.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        if (saved >= 0) runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
        runCatching { wake?.release() }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFY)
        super.onDestroy()
    }
}
