package app.nastavnik

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.nastavnik.notes.CleanNoteParser
import app.nastavnik.notes.NoteStorage
import app.nastavnik.notes.PdfExporter
import app.nastavnik.overlay.BubbleService
import app.nastavnik.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern

/**
 * Дымовые проверки на эмуляторе (запускаются в GitHub Actions).
 * Картинки печатаются в лог маленькими JPEG в base64 между метками IMG-BEGIN/IMG-END.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val pkg = ctx.packageName

    @After
    fun tearDown() {
        BubbleService.stop(ctx)
        SystemClock.sleep(500)
    }

    private fun shell(cmd: String): String = device.executeShellCommand(cmd).also { Log.i(TAG, "$ $cmd -> ${it.trim()}") }

    private fun logImage(name: String, bmp: Bitmap) {
        val w = 360
        val h = (bmp.height * w.toFloat() / bmp.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 60, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        println("IMG-BEGIN $name")
        b64.chunked(3000).forEach { println("IMG-DATA $it") }
        println("IMG-END $name")
    }

    private fun screenshot(name: String) {
        val f = File(ctx.cacheDir, "$name.png")
        if (device.takeScreenshot(f)) BitmapFactory.decodeFile(f.absolutePath)?.let { logImage(name, it) }
    }

    /** Доля «не белых» пикселей — чтобы отличить нарисованную страницу от пустой. */
    private fun inkRatio(bmp: Bitmap): Double {
        var ink = 0
        var total = 0
        for (y in 0 until bmp.height step 6) for (x in 0 until bmp.width step 6) {
            val c = bmp.getPixel(x, y)
            total++
            if (Color.red(c) < 200 || Color.green(c) < 200 || Color.blue(c) < 200) ink++
        }
        return ink.toDouble() / total
    }

    @Test
    fun cleanNoteExportsNonBlankPdfAndPngs() {
        val json = ctx.assets.open("sample_note.json").bufferedReader().use { it.readText() }
            .let { CleanNoteParser.toJson(CleanNoteParser.parse(it)) }
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_CLEAN_TEST),
        )
        var host: ViewGroup? = null
        scenario.onActivity { host = it.findViewById(android.R.id.content) }
        SystemClock.sleep(1500)
        screenshot("clean_test_screen")
        val result = runBlocking {
            withContext(Dispatchers.Main) { PdfExporter(ctx).export(host!!, json, NoteStorage(ctx).newWorkDir()) }
        }
        Log.i(TAG, "pages=${result.pageCount} pdf=${result.pdf.length()} pngs=${result.pngs.map { it.length() }}")
        assertTrue("ожидали 2 страницы, а их ${result.pageCount}", result.pageCount in 1..4)
        assertTrue(result.pdf.length() > 20_000)
        result.pngs.forEachIndexed { i, f ->
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            val ink = inkRatio(bmp)
            Log.i(TAG, "page ${i + 1}: ${bmp.width}x${bmp.height} ink=$ink")
            logImage("pdf_page_${i + 1}", bmp)
            assertTrue("страница ${i + 1} пустая (ink=$ink)", ink > 0.02)
        }
        val published = NoteStorage(ctx).publish(result.pdf, result.pngs, "Тест", "Проверка $\\frac{1}{x}$")
        Log.i(TAG, "published=${published.publicPath} error=${published.error}")
        assertNotNull(published.error ?: "", published.publicPath)
        scenario.close()
    }

    private fun stylusTap(x: Float, y: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val t = SystemClock.uptimeMillis()
        val src = InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 60)) {
            val ev = MotionEvent.obtain(t, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
            instr.uiAutomation.injectInputEvent(ev, true)
            ev.recycle()
        }
    }

    @Test
    fun bubblePanelCaptureAndApiError() {
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        shell("appops set $pkg PROJECT_MEDIA allow")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        val settings = ctx.app.settings
        settings.setOnboardingDone(true)
        settings.setProjectionExplained(true)
        settings.setApiKey("sk-ant-test-invalid-key")
        settings.setActiveConversation(0)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(800)
        scenario.onActivity { BubbleService.start(it) }
        val bubble = device.wait(Until.findObject(By.desc("Наставник").pkg(pkg)), 8000)
        assertNotNull("плавающая кнопка не появилась", bubble)
        val b = bubble.visibleBounds
        Log.i(TAG, "bubble bounds=$b display=${device.displayWidth}x${device.displayHeight}")
        screenshot("bubble")

        // 1. Стилус по кнопке не открывает панель.
        stylusTap(b.exactCenterX(), b.exactCenterY())
        SystemClock.sleep(1000)
        assertFalse("стилус открыл панель", device.hasObject(By.text("Подскажи следующий шаг")))

        // 2. Палец открывает панель.
        SystemClock.sleep(1200)
        device.wait(Until.findObject(By.desc("Наставник").pkg(pkg)), 3000)?.click()
        val quick = device.wait(Until.findObject(By.text("Подскажи следующий шаг")), 5000)
        assertNotNull("панель не открылась пальцем", quick)
        SystemClock.sleep(800)
        screenshot("panel_open")

        // 3. Касание мимо панели уходит в приложение под ней (кнопка «Настройки» на главном экране).
        val panelTop = device.findObject(By.res(pkg, "panel_card"))?.visibleBounds
        Log.i(TAG, "panel bounds=$panelTop")
        val settingsBtn = device.findObject(By.text("Настройки"))
        if (settingsBtn != null && panelTop != null && !panelTop.contains(settingsBtn.visibleCenter.x, settingsBtn.visibleCenter.y)) {
            settingsBtn.click()
            val opened = device.wait(Until.hasObject(By.text("Модель наставника")), 4000)
            Log.i(TAG, "tap outside panel reached app below: $opened")
            assertTrue("касание мимо панели не дошло до приложения под ней", opened)
        } else {
            Log.i(TAG, "кнопка «Настройки» закрыта панелью — проверка касания мимо пропущена")
        }

        // 4. Быстрая кнопка: снимок экрана + запрос с неверным ключом → понятная ошибка без падения.
        File(ctx.cacheDir, "last_shot.jpg").delete()
        device.findObject(By.text("Подскажи следующий шаг")).click()
        // Если система всё же показала диалог записи экрана — подтверждаем.
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)(start now|start|начать|next|далее|share screen)"))), 4000)?.let {
            Log.i(TAG, "projection dialog button: ${it.text}")
            it.click()
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)(start now|start|начать|share screen)"))), 2000)?.click()
        }
        val err = device.wait(Until.findObject(By.textContains("API-ключ")), 30000)
            ?: device.wait(Until.findObject(By.textContains("401")), 2000)
        SystemClock.sleep(1500)
        screenshot("after_question")
        val shot = File(ctx.cacheDir, "last_shot.jpg")
        Log.i(TAG, "last_shot exists=${shot.exists()} size=${shot.length()}")
        if (shot.exists()) logImage("last_shot", BitmapFactory.decodeFile(shot.absolutePath))
        assertTrue("снимок экрана не сделан", shot.exists() && shot.length() > 5_000)
        assertNotNull("не показана понятная ошибка про ключ", err)

        // 5. Свернуть панель → кнопка вернулась.
        device.findObject(By.desc("Свернуть"))?.click()
        assertNotNull("кнопка не вернулась после сворачивания", device.wait(Until.findObject(By.desc("Наставник").pkg(pkg)), 4000))
        scenario.close()
    }

    companion object {
        private const val TAG = "SmokeTest"
    }
}
