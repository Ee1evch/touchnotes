package app.nastavnik.campus

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.sqrt

/**
 * Живое «где я» на телефоне: шаги, компас, барометр, Wi-Fi и GPS → [Positioning].
 * Всё считается в своём потоке; наружу — [fix]. Сканы Wi-Fi — раз в 30 с (Android разрешает 4 скана за 2 минуты),
 * между ними место ведут шаги. Работает только пока открыта карта.
 */
class PositionTracker(private val context: Context, private val repo: CampusRepo) {
    data class State(val site: Site, val fix: Positioning.Fix, val at: Long)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state
    private val _status = MutableStateFlow("")
    /** Что сейчас мешает или происходит: «Ищу сети Wi-Fi…», «Нет датчика шагов» и т. п. */
    val status: StateFlow<String> = _status

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var positioning: Positioning? = null
    private var site: Site? = null
    private var sites: List<Site> = emptyList()
    private var lastScan: Map<String, Int> = emptyMap()
    private var yaw = Double.NaN
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val locations = context.getSystemService(LocationManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Mine(val floor: Int, val x: Double, val y: Double, val scan: Map<String, Int>)

    private fun mineFile(s: Site) = File(context.filesDir, "campus/${s.id}/my_wifi.json")

    private fun mine(s: Site): List<Mine> = runCatching { json.decodeFromString<List<Mine>>(mineFile(s).readText()) }.getOrDefault(emptyList())

    val running: Boolean get() = thread != null

    suspend fun start(sites: List<Site>) {
        if (thread != null) return
        this.sites = sites
        val t = HandlerThread("position").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        _status.value = "Ищу сети Wi-Fi…"
        // Датчики.
        val rotation = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val stepSensor = sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            ?.takeIf { ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED }
        val accel = if (stepSensor == null) sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        val baro = sensors?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        rotation?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, h) }
        stepSensor?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        accel?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, h) }
        baro?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, h) }
        ContextCompat.registerReceiver(context, scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), null, h, ContextCompat.RECEIVER_NOT_EXPORTED)
        requestGps(h)
        // Сразу — то, что телефон уже видел, потом свежий скан и дальше раз в 30 с.
        h.post { onScanResults() }
        h.post(scanLoop)
    }

    fun stop() {
        val t = thread ?: return
        sensors?.unregisterListener(listener)
        runCatching { context.unregisterReceiver(scanReceiver) }
        runCatching { locations?.removeUpdates(gpsListener) }
        handler?.removeCallbacksAndMessages(null)
        t.quitSafely()
        thread = null
        handler = null
        positioning = null
        site = null
        _state.value = null
        _status.value = ""
    }

    /** «Я здесь»: поправить место вручную и запомнить скан как свой отпечаток — в следующий раз узнаю точнее. */
    fun correct(s: Site, floor: Int, x: Double, y: Double) {
        handler?.post {
            val loc = positioning?.wifi
            val scan = lastScan
            if (site?.id == s.id && loc != null && scan.isNotEmpty()) {
                loc.fingerprintOf(scan, floor, x, y)?.let { fp ->
                    val list = (mine(s) + Mine(floor, x, y, scan)).takeLast(300)
                    runCatching { mineFile(s).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(list)) }
                    positioning?.wifi = loc.plus(listOf(fp))
                }
            }
            if (site?.id != s.id) {
                site = s
                positioning = null
            }
            val p = positioning ?: kotlinx.coroutines.runBlocking { make(s) }?.also { positioning = it } ?: return@post
            p.reset(floor, x, y)
            publish()
        }
    }

    private suspend fun make(s: Site): Positioning? {
        val base = repo.locator(s) ?: return null
        val extra = mine(s).mapNotNull { base.fingerprintOf(it.scan, it.floor, it.x, it.y) }
        return Positioning(s, base.plus(extra), { f -> runCatching { repo.grid(s, f) }.getOrNull() })
    }

    private val scanLoop = object : Runnable {
        override fun run() {
            @Suppress("DEPRECATION")
            runCatching { wifiManager?.startScan() }
            handler?.postDelayed(this, 30_000)
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { onScanResults() }
    }

    @SuppressLint("MissingPermission")
    private fun onScanResults() {
        val scan = runCatching { wifiManager?.scanResults.orEmpty().associate { it.BSSID to it.level } }.getOrDefault(emptyMap())
        if (scan.isEmpty()) {
            _status.value = if (wifiManager?.isWifiEnabled == false) "Включи Wi-Fi — по нему определяется место" else "Сетей Wi-Fi пока не видно"
            return
        }
        lastScan = scan
        val now = SystemClock.elapsedRealtime()
        if (positioning == null) {
            // Здание — то, чьих точек доступа видно больше всего.
            val best = sites.mapNotNull { s -> kotlinx.coroutines.runBlocking { repo.locator(s) }?.let { s to it.known(scan) } }
                .maxByOrNull { it.second }
            if (best == null || best.second < 3) {
                _status.value = "Не узнаю сети вокруг — ты в кампусе?"
                return
            }
            site = best.first
            positioning = kotlinx.coroutines.runBlocking { make(best.first) }
        }
        positioning?.onWifi(scan, now)
        publish()
    }

    private fun publish() {
        val s = site ?: return
        val fix = positioning?.estimate() ?: return
        _status.value = ""
        _state.value = State(s, fix, System.currentTimeMillis())
    }

    // Запасной датчик шагов по акселерометру: пик модуля ускорения выше порога, не чаще раза в 0,3 с.
    private var accLow = 9.8f
    private var lastPeak = 0L
    private var above = false

    private val rot = FloatArray(9)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            when (e.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rot, e.values)
                    // Азимут оси Y телефона в системе «восток, север».
                    yaw = kotlin.math.atan2(rot[1].toDouble(), rot[4].toDouble())
                }
                Sensor.TYPE_STEP_DETECTOR -> step(now)
                Sensor.TYPE_ACCELEROMETER -> {
                    val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    accLow = accLow * 0.95f + m * 0.05f
                    if (!above && m > accLow + 1.6f && now - lastPeak > 300) { above = true; lastPeak = now; step(now) }
                    if (above && m < accLow) above = false
                }
                Sensor.TYPE_PRESSURE -> positioning?.onPressure(e.values[0].toDouble(), now)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private var lastPublish = 0L

    private fun step(now: Long) {
        val p = positioning ?: return
        if (yaw.isNaN()) return
        p.onStep(yaw, now)
        // Перерисовка не чаще 3 раз в секунду.
        if (now - lastPublish > 330) { lastPublish = now; publish() }
    }

    @SuppressLint("MissingPermission")
    private fun requestGps(h: Handler) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching { locations?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, gpsListener, h.looper) }
    }

    private val gpsListener = LocationListener { loc ->
        val s = site ?: return@LocationListener
        val (x, y) = s.planOf(loc.latitude, loc.longitude) ?: return@LocationListener
        positioning?.onGps(x, y, loc.accuracy.toDouble())
    }
}
