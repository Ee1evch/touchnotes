package app.nastavnik.study

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.ui.WakeActivity
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Звонок будильника. Отдельный сервис, а не только экран: звук идёт, даже если экран будильника не открылся
 * (нет разрешения на полноэкранное уведомление, телефон разблокирован и т. п.).
 */
class WakeService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private var savedVolume = -1
    private var startedAt = 0L
    private val main = Handler(Looper.getMainLooper())

    companion object {
        /** Звонит ли сейчас (для защиты — какой экран вернуть). */
        @Volatile var ringing = false
            private set
        /** Пробный звонок из настроек — экран будильника выключается без примера. */
        @Volatile var test = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        test = intent?.getBooleanExtra(WakeAlarm.EXTRA_TEST, false) == true
        // Сразу в передний план — иначе система остановит сервис.
        val n = notification()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(WakeAlarm.NOTIFY_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(WakeAlarm.NOTIFY_RING, n)
        }.onFailure {
            // Не дали стать сервисом переднего плана — хотя бы громкое уведомление на канале будильника.
            runCatching { getSystemService(NotificationManager::class.java).notify(WakeAlarm.NOTIFY_RING, n) }
        }
        if (!ringing) {
            ringing = true
            startedAt = System.currentTimeMillis()
            val w = WakePrefs.of(this)
            // Будильник сработал — старая отметка «отложено до» больше не нужна.
            if (!test) w.snoozeUntil = 0
            // Пока звонит — нельзя удалить приложение и выключить телефон (до начала пары, но не дольше 3 часов).
            val now = System.currentTimeMillis()
            w.guardUntil = if (test) now + 60_000L else maxOf(now + 20 * 60_000L, minOf(w.lessonAt, now + 3 * 3_600_000L))
            wake = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nastavnik:wake")
                ?.apply { setReferenceCounted(false); acquire(WakeAlarm.RING_LIMIT_MS + 60_000L) }
            startSound()
            startVibration()
            openScreen()
            main.postDelayed(::timeout, if (test) 60_000L else WakeAlarm.RING_LIMIT_MS)
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val full = PendingIntent.getActivity(
            this, WakeAlarm.NOTIFY_RING,
            Intent(this, WakeActivity::class.java).putExtra(WakeAlarm.EXTRA_TEST, test)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getBroadcast(
            this, WakeAlarm.NOTIFY_RING + 1, Intent(this, WakeReceiver::class.java).setAction(WakeAlarm.ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val now = LocalDateTime.now()
        return NotificationCompat.Builder(this, NastavnikApp.CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Подъём · ${WakeAlarm.hm(now)}")
            .setContentText("Открой, чтобы выключить")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .setOngoing(true)
            .setSilent(true)
            .apply { if (!test && WakeAlarm.canSnooze(this@WakeService)) addAction(0, "Ещё ${WakeAlarm.SNOOZE_MIN} минут", snooze) }
            .build()
    }

    /** Экран будильника поверх блокировки; если система не даёт — остаётся полноэкранное уведомление. */
    private fun openScreen() {
        if (!Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(
                Intent(this, WakeActivity::class.java).putExtra(WakeAlarm.EXTRA_TEST, test)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            )
        }
    }

    private fun startSound() {
        val audio = getSystemService(AudioManager::class.java)
        // Тихий будильник не разбудит: поднимаем громкость будильника минимум до 80 % (после — вернём).
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val need = (max * 0.8f).toInt().coerceAtLeast(1)
            if (cur < need) {
                savedVolume = cur
                audio.setStreamVolume(AudioManager.STREAM_ALARM, need, 0)
            }
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val uris = listOfNotNull(
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in uris) {
            val p = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(this@WakeService, uri)
                    isLooping = true
                    setVolume(0.35f, 0.35f)
                    prepare()
                    start()
                }
            }.getOrNull()
            if (p != null) { player = p; break }
        }
        // Звук нарастает за 40 секунд — проснуться мягко, но наверняка.
        val ramp = object : Runnable {
            override fun run() {
                val p = player ?: return
                val t = (System.currentTimeMillis() - startedAt) / 40_000f
                val v = (0.35f + 0.65f * t).coerceAtMost(1f)
                runCatching { p.setVolume(v, v) }
                if (v < 1f) main.postDelayed(this, 2_000L)
            }
        }
        main.postDelayed(ramp, 2_000L)
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator else legacyVibrator()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        @Suppress("DEPRECATION")
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0), attrs) }
    }

    @Suppress("DEPRECATION")
    private fun legacyVibrator(): Vibrator? = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** Не выключил за 10 минут — замолкаем и через 3 минуты звоним снова. */
    private fun timeout() {
        if (!ringing) return
        if (test) { WakePrefs.of(this).guardUntil = 0; stopSelf(); return }
        app.appScope.launch { runCatching { WakeAlarm.ringAgainLater(this@WakeService) } }
        stopSelf()
    }

    override fun onDestroy() {
        ringing = false
        main.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        if (savedVolume >= 0) runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
        runCatching { wake?.release() }
        getSystemService(NotificationManager::class.java)?.cancel(WakeAlarm.NOTIFY_RING)
        super.onDestroy()
    }
}
