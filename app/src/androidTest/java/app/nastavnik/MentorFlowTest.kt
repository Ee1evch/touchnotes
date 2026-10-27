package app.nastavnik

import android.graphics.Bitmap
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.nastavnik.overlay.AnswerOverlay
import app.nastavnik.overlay.BubbleService
import app.nastavnik.overlay.OverlayWindows
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
 * вопрос → потоковый ответ с меткой места и [[ГОТОВО]] → рамка и заметка прямо на экране →
 * «Сделать чистовик» → предпросмотр → сохранение. Отдельно: рамка едет вместе с прокруткой.
 */
@RunWith(AndroidJUnit4::class)
class MentorFlowTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val pkg = ctx.packageName
    private lateinit var server: MockWebServer
    private val bodies = CopyOnWriteArrayList<String>()
    private val sizeRe = Regex("""\[Снимок экрана: (\d+)×(\d+) пикселей]""")

    /** Рамка: от 25% до 60% ширины снимка и от 30% до 40% высоты — в пикселях присланного снимка. */
    private fun answer(w: Int, h: Int): String =
        "@@ ok ${w / 4} ${h * 3 / 10} ${w * 6 / 10} ${h * 4 / 10}\n" +
            "Верно! Производная \$f'(x) = 3x^2 - 3\$ найдена правильно, и точки \$x = \\pm 1\$ тоже.\n\n" +
            "\$\$f(-1) = 2,\\quad f(1) = -2\$\$\n[[ГОТОВО]]"

    private fun sse(text: String): String = buildString {
        append("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}\n\n")
        append("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n")
        // Отдаём ответ кусочками, как настоящий стрим.
        text.chunked(12).forEach { part ->
            val t = JsonPrimitive(part).toString() // уже в кавычках и экранирован
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":$t}}\n\n")
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
                    val m = sizeRe.find(body)
                    val w = m?.groupValues?.get(1)?.toInt() ?: 1000
                    val h = m?.groupValues?.get(2)?.toInt() ?: 700
                    MockResponse().setHeader("content-type", "text/event-stream").setBody(sse(answer(w, h)))
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
        s.setWatchPauseSec(0)
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

    private fun askHint() {
        val bubble = device.wait(Until.findObject(By.desc("Наставник").pkg(pkg)), 15000)
        if (bubble == null) screenshot("no_bubble")
        assertNotNull("плавающая кнопка не появилась", bubble)
        bubble!!.click()
        device.wait(Until.findObject(By.text("Подсказка")), 8000)!!.click()
    }

    /** Где на экране должна быть рамка: снимок — это экран без системных панелей. */
    private fun expectedMark(): Rect {
        val cr = OverlayWindows.contentRect(ctx)
        return Rect(
            cr.left + cr.width() / 4, cr.top + cr.height() * 3 / 10,
            cr.left + cr.width() * 6 / 10, cr.top + cr.height() * 4 / 10,
        )
    }

    @Test
    fun answerIsDrawnOnTheScreenThenCleanCopyIsSaved() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(800)
        scenario.onActivity { BubbleService.start(it) }
        askHint()

        // Ответ пришёл → рамка на месте, рядом заметка с кнопкой «Сделать чистовик».
        val cleanBtn = device.wait(Until.findObject(By.text("Сделать чистовик")), 40000)
        SystemClock.sleep(1500)
        screenshot("flow_answer")
        assertNotNull("нет кнопки «Сделать чистовик» в заметке", cleanBtn)
        val mark = AnswerOverlay.shownMarks.singleOrNull()
        assertNotNull("рамка не нарисована", mark)
        val note = device.findObject(By.desc("Заметка: Верно"))
        assertNotNull("заметка не показана", note)
        val exp = expectedMark()
        val got = Rect(mark!!.left, mark.top, mark.right, mark.bottom)
        Log.i("MentorFlowTest", "mark expected=$exp got=$got note=${note.visibleBounds}")
        val tol = device.displayWidth / 40
        assertTrue("рамка не на месте: ждали центр (${exp.centerX()}, ${exp.centerY()}), а она $got",
            kotlin.math.abs(got.centerX() - exp.centerX()) <= tol && kotlin.math.abs(got.centerY() - exp.centerY()) <= tol)
        assertTrue("заметка закрыла отмеченное место", !Rect.intersects(note.visibleBounds, exp))

        // Первый запрос: стрим, картинка и размер снимка в последнем сообщении, промт с координатами.
        val first = Json.parseToJsonElement(bodies.first()).jsonObject
        val msgs = first["messages"]!!.jsonArray
        val lastContent = msgs.last().jsonObject["content"] as JsonArray
        assertEquals("image", (lastContent[0].jsonObject["type"] as JsonPrimitive).content)
        assertTrue(bodies.first().contains("[Снимок экрана:"))
        val system = (first["system"] as JsonPrimitive).content
        assertTrue(system.contains("Ты — наставник"))
        assertTrue(system.contains("в пикселях"))

        // Чистовик: снимок → «Собрать» → предпросмотр открывается сам.
        cleanBtn.click()
        device.wait(Until.findObject(By.text("Собрать")), 15000)!!.click()
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

    @Test
    fun markFollowsScrolledPage() {
        // Длинный экран настроек — его можно прокрутить, как страницу в Touchnotes.
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_SETTINGS),
        )
        SystemClock.sleep(1200)
        scenario.onActivity { BubbleService.start(it) }
        askHint()
        assertNotNull("нет заметки", device.wait(Until.findObject(By.desc("Заметка: Верно")), 40000))
        SystemClock.sleep(5000) // пусть слежение сделает пару кадров
        val before = AnswerOverlay.shownMarks.singleOrNull()
        assertNotNull("рамка не нарисована", before)
        val label = device.findObject(By.textStartsWith("Модель наставника"))
        val labelBefore = label?.visibleBounds
        screenshot("scroll_before")
        val x = device.displayWidth / 4
        device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight * 2 / 4, 80)
        SystemClock.sleep(7000)
        screenshot("scroll_follow")
        val after = AnswerOverlay.shownMarks.singleOrNull()
        val labelAfter = device.findObject(By.textStartsWith("Модель наставника"))?.visibleBounds
        Log.i("MentorFlowTest", "scroll: mark $before -> $after, label $labelBefore -> $labelAfter")
        assertNotNull("рамка пропала после прокрутки", after)
        assertNotNull("не найден ориентир на странице", labelBefore)
        if (labelBefore != null && labelAfter != null) {
            val pageDy = labelAfter.top - labelBefore.top
            val markDy = after!!.top - before!!.top
            assertTrue("страница не прокрутилась ($pageDy)", kotlin.math.abs(pageDy) > 60)
            assertTrue("рамка не поехала вместе со страницей: страница $pageDy, рамка $markDy",
                kotlin.math.abs(markDy - pageDy) <= device.displayHeight / 25)
        }
        scenario.close()
    }
}
