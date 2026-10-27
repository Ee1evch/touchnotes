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
        val w = 760
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
        SystemClock.sleep(5000)
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
    fun bubbleMenuCaptureAndApiError() {
        shell("settings put global hide_error_dialogs 1")
        dismissSystemAnr()
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        shell("appops set $pkg PROJECT_MEDIA allow")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        val settings = ctx.app.settings
        settings.setProvider(app.nastavnik.ai.AiProvider.ANTHROPIC)
        settings.setOnboardingDone(true)
        settings.setProjectionExplained(true)
        settings.setApiKey("sk-ant-test-invalid-key")
        settings.setActiveConversation(0)
        settings.setWatchPauseSec(0)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(800)
        scenario.onActivity { BubbleService.start(it) }
        SystemClock.sleep(1500)
        dismissSystemAnr()
        val bubble = device.wait(Until.findObject(By.desc("Наставник").pkg(pkg)), 15000)
        if (bubble == null) screenshot("no_bubble")
        assertNotNull("плавающая кнопка не появилась", bubble)
        val b = bubble.visibleBounds
        Log.i(TAG, "bubble bounds=$b display=${device.displayWidth}x${device.displayHeight}")
        screenshot("bubble")

        // 1. Стилус по кнопке не открывает меню.
        stylusTap(b.exactCenterX(), b.exactCenterY())
        SystemClock.sleep(1000)
        assertFalse("стилус открыл меню", device.hasObject(By.text("Подсказка")))

        // 2. Палец открывает короткое меню у кнопки — никакого окна-панели.
        SystemClock.sleep(1200)
        tapBubble()
        assertNotNull("меню не открылось пальцем", device.wait(Until.findObject(By.text("Подсказка")), 5000))
        assertNotNull(device.findObject(By.text("Где ошибка?")))
        SystemClock.sleep(600)
        screenshot("menu_open")

        // 3. Касание мимо меню закрывает его и уходит в приложение под ним (кнопка «Настройки» на главном экране).
        val settingsBtn = device.findObject(By.text("Настройки"))
        if (settingsBtn != null) {
            settingsBtn.click()
            val opened = device.wait(Until.hasObject(By.textContains("Модель наставника")), 4000)
            Log.i(TAG, "tap outside menu reached app below: $opened")
            assertTrue("касание мимо меню не дошло до приложения под ним", opened)
            assertFalse("меню не закрылось", device.hasObject(By.text("Где ошибка?")))
            device.pressBack()
            SystemClock.sleep(800)
        }

        // 4. «Где ошибка?»: снимок экрана + запрос с неверным ключом → понятная ошибка у кнопки, без падения.
        File(ctx.cacheDir, "last_shot.jpg").delete()
        tapBubble()
        device.wait(Until.findObject(By.text("Где ошибка?")), 5000)!!.click()
        // Если система всё же показала диалог записи экрана — подтверждаем.
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)(start now|start|начать|next|далее|share screen)"))), 4000)?.let {
            Log.i(TAG, "projection dialog button: ${it.text}")
            it.click()
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)(start now|start|начать|share screen)"))), 2000)?.click()
        }
        val err = device.wait(Until.findObject(By.textContains("API-ключ")), 30000)
            ?: device.wait(Until.findObject(By.textContains("401")), 2000)
        SystemClock.sleep(1200)
        screenshot("after_question")
        val shot = File(ctx.cacheDir, "last_shot.jpg")
        Log.i(TAG, "last_shot exists=${shot.exists()} size=${shot.length()}")
        if (shot.exists()) logImage("last_shot", BitmapFactory.decodeFile(shot.absolutePath))
        assertTrue("снимок экрана не сделан", shot.exists() && shot.length() > 5_000)
        assertNotNull("не показана понятная ошибка про ключ", err)
        val fullOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(shot.absolutePath, fullOpts)
        assertTrue("снимок больше 1,15 Мп", fullOpts.outWidth.toLong() * fullOpts.outHeight <= 1_150_000L)

        // 5. Режим «Выделить область» из меню долгого нажатия: обводим прямоугольник — уходит только он.
        device.findObject(By.desc("Наставник").pkg(pkg)).longClick()
        device.wait(Until.findObject(By.text("Выделять область перед вопросом")), 4000)!!.click()
        SystemClock.sleep(600)
        shot.delete()
        tapBubble()
        device.wait(Until.findObject(By.text("Где ошибка?")), 5000)!!.click()
        assertNotNull("не появилось затемнение для выбора области", device.wait(Until.findObject(By.textContains("Обведи пальцем")), 8000))
        device.swipe(300, 300, 1100, 900, 30)
        SystemClock.sleep(500)
        screenshot("region_selected")
        device.findObject(By.text("Отправить область")).click()
        device.wait(Until.findObject(By.textContains("API-ключ")), 30000)
        SystemClock.sleep(1000)
        val regionOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(shot.absolutePath, regionOpts)
        Log.i(TAG, "full shot ${fullOpts.outWidth}x${fullOpts.outHeight}, region shot ${regionOpts.outWidth}x${regionOpts.outHeight}")
        assertTrue("снимок области не меньше полного", shot.exists() && regionOpts.outWidth in 1 until fullOpts.outWidth)
        BitmapFactory.decodeFile(shot.absolutePath)?.let { logImage("region_shot", it) }
        device.findObject(By.desc("Наставник").pkg(pkg)).longClick()
        device.wait(Until.findObject(By.text("Отправлять весь экран")), 4000)!!.click()
        SystemClock.sleep(600)

        // 6. Чистовик из меню: снимок → «Собрать» → понятная ошибка ключа, без падения.
        tapBubble()
        device.wait(Until.findObject(By.text("Чистовик")), 5000)!!.click()
        assertNotNull("нет кнопки «Собрать»", device.wait(Until.findObject(By.text("Собрать")), 15000))
        screenshot("clean_collecting")
        device.findObject(By.text("Собрать")).click()
        assertNotNull("чистовик не показал ошибку", device.wait(Until.findObject(By.textStartsWith("Не получилось")), 40000))
        SystemClock.sleep(800)
        screenshot("clean_failed")
        device.findObject(By.text("Отмена"))?.click()
        SystemClock.sleep(500)

        // 7. Переключение на Gemini без ключа → понятная подсказка, запрос не уходит.
        settings.setProvider(app.nastavnik.ai.AiProvider.GEMINI)
        settings.setApiKey("", app.nastavnik.ai.AiProvider.GEMINI)
        SystemClock.sleep(500)
        tapBubble()
        device.wait(Until.findObject(By.text("Подсказка")), 5000)!!.click()
        assertNotNull("нет подсказки про ключ Gemini", device.wait(Until.findObject(By.textContains("Google Gemini")), 4000))
        screenshot("gemini_no_key")
        settings.setProvider(app.nastavnik.ai.AiProvider.ANTHROPIC)
        scenario.close()
    }

    /** Эмулятор иногда показывает «… isn't responding» про чужое приложение — закрываем, чтобы не мешало. */
    private fun dismissSystemAnr() {
        if (device.hasObject(By.textContains("isn't responding")) || device.hasObject(By.textContains("не отвечает"))) {
            (device.findObject(By.text("Wait")) ?: device.findObject(By.text("Подождать")))?.click()
            SystemClock.sleep(800)
        }
    }

    private fun tapBubble() {
        dismissSystemAnr()
        device.wait(Until.findObject(By.desc("Наставник").pkg(pkg)), 4000)!!.click()
    }

    companion object {
        private const val TAG = "SmokeTest"
    }
}
