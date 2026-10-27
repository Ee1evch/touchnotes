package app.nastavnik

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.nastavnik.overlay.BubbleService
import app.nastavnik.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Полный сценарий на эмуляторе с мок-сервером вместо api.anthropic.com:
 * вопрос → потоковый ответ с формулами и [[ГОТОВО]] → плашка → чистовик → предпросмотр → сохранение.
 */
@RunWith(AndroidJUnit4::class)
class MentorFlowTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val pkg = ctx.packageName
    private lateinit var server: MockWebServer
    private val bodies = CopyOnWriteArrayList<String>()

    private val answer = "Верно! Производная \$f'(x) = 3x^2 - 3\$ найдена правильно, и точки \$x = \\pm 1\$ тоже.\n\n" +
        "\$\$f(-1) = 2,\\quad f(1) = -2\$\$\n\nРешение доведено до ответа.\n[[ГОТОВО]]"

    private fun sse(): String = buildString {
        append("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}\n\n")
        append("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n")
        // Отдаём ответ кусочками, как настоящий стрим.
        answer.chunked(12).forEach { part ->
            val text = JsonPrimitive(part).toString() // уже в кавычках и экранирован
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":$text}}\n\n")
        }
        append("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n")
        append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
    }

    @Before
    fun setUp() {
        val note = ctx.assets.open("sample_note.json").bufferedReader().use { it.readText() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                bodies += body
                return if (body.contains("\"stream\":true")) {
                    MockResponse().setHeader("content-type", "text/event-stream").setBody(sse())
                } else {
                    // Модель «обернула» JSON в ```json — парсер должен справиться.
                    val text = "Вот чистовик:\n```json\n$note\n```"
                    val resp = buildJsonObject {
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                        put("stop_reason", "end_turn")
                    }
                    MockResponse().setHeader("content-type", "application/json").setBody(resp.toString())
                }
            }
        }
        server.start()
        device.executeShellCommand("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        device.executeShellCommand("appops set $pkg PROJECT_MEDIA allow")
        val s = ctx.app.settings
        s.setProvider(app.nastavnik.ai.AiProvider.ANTHROPIC)
        s.setOnboardingDone(true)
        s.setProjectionExplained(true)
        s.setApiKey("sk-ant-test")
        s.setActiveConversation(0)
        s.setDebugBaseUrl(server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        ctx.app.settings.setDebugBaseUrl(null)
        BubbleService.stop(ctx)
        server.shutdown()
        SystemClock.sleep(500)
    }

    private fun screenshot(name: String) {
        val f = File(ctx.cacheDir, "$name.png")
        if (!device.takeScreenshot(f)) return
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return
        val w = 760
        val small = Bitmap.createScaledBitmap(bmp, w, (bmp.height * w.toFloat() / bmp.width).toInt(), true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 60, out)
        println("IMG-BEGIN $name")
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).chunked(3000).forEach { println("IMG-DATA $it") }
        println("IMG-END $name")
    }

    @Test
    fun questionDoneBannerCleanPreviewAndSave() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(800)
        scenario.onActivity { BubbleService.start(it) }
        device.wait(Until.findObject(By.desc("Наставник").pkg(pkg)), 8000)!!.click()
        device.wait(Until.findObject(By.text("Подскажи следующий шаг").enabled(true)), 8000)!!.click()

        // Потоковый ответ закончился → маркер спрятан, показана плашка.
        val banner = device.wait(Until.findObject(By.text("Решение готово. Сделать чистовик?")), 40000)
        SystemClock.sleep(1500)
        screenshot("flow_answer")
        assertNotNull("нет плашки «Решение готово»", banner)

        // Первый запрос: стрим, картинка в последнем сообщении, системный промт наставника.
        val first = Json.parseToJsonElement(bodies.first()).jsonObject
        val msgs = first["messages"]!!.jsonArray
        val lastContent = msgs.last().jsonObject["content"] as JsonArray
        assertEquals("image", (lastContent[0].jsonObject["type"] as JsonPrimitive).content)
        assertTrue((first["system"] as JsonPrimitive).content.contains("Ты — наставник"))

        // Чистовик: снимок → сборка → предпросмотр.
        device.findObject(By.res(pkg, "done_clean")).click()
        device.wait(Until.findObject(By.text("Собрать чистовик").enabled(true)), 15000)!!.click()
        assertNotNull("нет предпросмотра", device.wait(Until.findObject(By.text("Сохранить")), 40000))
        SystemClock.sleep(4000)
        screenshot("flow_preview")

        // Сохранить → PDF в «Документы», запись в истории, открывается отправка.
        device.findObject(By.text("Сохранить")).click()
        val saved = runBlocking {
            var list = ctx.app.db.cleanNotes().observeAll().first()
            val until = SystemClock.uptimeMillis() + 40000
            while (list.isEmpty() && SystemClock.uptimeMillis() < until) {
                SystemClock.sleep(500)
                list = ctx.app.db.cleanNotes().observeAll().first()
            }
            list
        }
        SystemClock.sleep(2500)
        screenshot("flow_after_save")
        assertTrue("чистовик не сохранился", saved.isNotEmpty())
        val n = saved.first()
        Log.i("MentorFlowTest", "saved: title=${n.title} public=${n.publicPath} pdf=${n.pdfPath}")
        assertTrue(File(n.pdfPath!!).length() > 20_000)
        assertNotNull(n.publicPath)
        device.pressBack()
        scenario.close()
    }
}
