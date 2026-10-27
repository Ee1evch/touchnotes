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
import app.nastavnik.session.ShotGeometry
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

    /** Текст на экране, который «нейросеть» должна отметить (его границы на экране). */
    @Volatile
    private var target: Rect? = null

    /**
     * Ответ «нейросети»: номер строки, в которой на снимке стоит [target]. Номер берём из той же
     * разметки строк, что приложение нарисовало на поле слева от снимка, — как сделала бы настоящая модель.
     */
    private fun answer(): String {
        var spec = "стр 1"
        val g = ShotGeometry.last
        val t = target
        val lines = g?.lines?.lines.orEmpty()
        if (g != null && t != null && lines.isNotEmpty()) {
            val cy = ((t.centerY() - g.screen.top) * g.imageH.toFloat() / g.screen.height).toInt()
            val cx = ((t.centerX() - g.screen.left) * g.imageW.toFloat() / g.screen.width).toInt()
            val line = lines.firstOrNull { cy in it.top..it.bottom && cx in it.left..it.right }
                ?: lines.minBy { kotlin.math.abs(it.centerY - cy) }
            spec = "стр ${line.number}"
            Log.i("MentorFlowTest", "target=$t -> line ${line.number} (${line.left},${line.top})-(${line.right},${line.bottom}) of ${lines.size}")
        }
        return "@@ ok $spec\n" +
            "Верно! Производная \$f'(x) = 3x^2 - 3\$ найдена правильно, и точки \$x = \\pm 1\$ тоже.\n\n" +
            "\$\$f(-1) = 2,\\quad f(1) = -2\$\$\n[[ГОТОВО]]"
    }

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
                    MockResponse().setHeader("content-type", "text/event-stream").setBody(sse(answer()))
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

    /** Эмулятор иногда показывает «… isn't responding» про чужое приложение — закрываем, чтобы не мешало. */
    private fun dismissSystemAnr() {
        if (device.hasObject(By.textContains("isn't responding")) || device.hasObject(By.textContains("не отвечает"))) {
            (device.findObject(By.text("Wait")) ?: device.findObject(By.text("Подождать")))?.click()
            SystemClock.sleep(800)
        }
    }

    private fun askHint() {
        dismissSystemAnr()
        val bubble = device.wait(Until.findObject(By.desc("Наставник").pkg(pkg)), 15000)
        if (bubble == null) screenshot("no_bubble")
        assertNotNull("плавающая кнопка не появилась", bubble)
        bubble!!.click()
        device.wait(Until.findObject(By.text("Подсказка")), 8000)!!.click()
    }

    /** Отметка без полей окна (поле под значок — 12 dp с каждой стороны). */
    private fun inner(r: app.nastavnik.geom.IRect): Rect {
        val pad = (12 * ctx.resources.displayMetrics.density + 0.5f).toInt()
        return Rect(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
    }

    /** Отметка легла точно на нужную строку: накрывает текст и не больше пары строк по высоте. */
    private fun assertMarkOn(text: Rect, mark: Rect) {
        Log.i("MentorFlowTest", "text=$text mark=$mark")
        assertTrue("отметка $mark не накрывает текст $text", mark.contains(text.centerX(), text.centerY()))
        assertTrue("отметка выше строки: ${mark.height()} при тексте ${text.height()}", mark.height() <= text.height() * 3)
        assertTrue("отметка сдвинута по вертикали", kotlin.math.abs(mark.centerY() - text.centerY()) <= text.height())
    }

    @Test
    fun answerIsDrawnOnTheScreenThenCleanCopyIsSaved() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(800)
        scenario.onActivity { BubbleService.start(it) }
        target = device.wait(Until.findObject(By.text("Как это работает")), 5000)?.visibleBounds
        assertNotNull("на главном экране нет заголовка «Как это работает»", target)
        askHint()

        // Ответ пришёл → строка подсвечена, рядом заметка с кнопкой «Сделать чистовик».
        val cleanBtn = device.wait(Until.findObject(By.text("Сделать чистовик")), 40000)
        SystemClock.sleep(1500)
        screenshot("flow_answer")
        assertNotNull("нет кнопки «Сделать чистовик» в заметке", cleanBtn)
        val mark = AnswerOverlay.shownMarks.singleOrNull()
        assertNotNull("рамка не нарисована", mark)
        val note = device.findObject(By.desc("Заметка: Верно"))
        assertNotNull("заметка не показана", note)
        val got = inner(mark!!)
        assertMarkOn(target!!, got)
        assertTrue("заметка закрыла отмеченное место", !Rect.intersects(note.visibleBounds, got))

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
        // Отмечаем какой-нибудь текст посередине экрана — после прокрутки он останется виден.
        SystemClock.sleep(800)
        val h = device.displayHeight
        val pick = device.findObjects(By.pkg(pkg).clazz("android.widget.TextView"))
            .filter { o -> (o.text?.length ?: 0) >= 6 && o.visibleBounds.centerY() in (h * 35 / 100)..(h * 65 / 100) && o.visibleBounds.centerX() < device.displayWidth * 6 / 10 }
            .minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - h / 2) }
        assertNotNull("нет текста посередине экрана настроек", pick)
        val labelText = pick!!.text
        target = pick.visibleBounds
        askHint()
        assertNotNull("нет заметки", device.wait(Until.findObject(By.desc("Заметка: Верно")), 40000))
        SystemClock.sleep(5000) // пусть слежение сделает пару кадров
        val before = AnswerOverlay.shownMarks.singleOrNull()
        assertNotNull("рамка не нарисована", before)
        val labelBefore = device.findObject(By.text(labelText))?.visibleBounds
        assertMarkOn(labelBefore!!, inner(before!!))
        screenshot("scroll_before")
        val x = device.displayWidth / 4
        device.swipe(x, h * 3 / 4, x, h * 9 / 16, 80)
        SystemClock.sleep(7000)
        screenshot("scroll_follow")
        val after = AnswerOverlay.shownMarks.singleOrNull()
        val labelAfter = device.findObject(By.text(labelText))?.visibleBounds
        Log.i("MentorFlowTest", "scroll: mark $before -> $after, label $labelBefore -> $labelAfter")
        assertNotNull("рамка пропала после прокрутки", after)
        assertNotNull("не найден ориентир на странице", labelBefore)
        if (labelBefore != null && labelAfter != null) {
            val pageDy = labelAfter.top - labelBefore.top
            val markDy = after!!.top - before.top
            assertMarkOn(labelAfter, inner(after))
            assertTrue("страница не прокрутилась ($pageDy)", kotlin.math.abs(pageDy) > 60)
            assertTrue("рамка не поехала вместе со страницей: страница $pageDy, рамка $markDy",
                kotlin.math.abs(markDy - pageDy) <= device.displayHeight / 25)
        }
        scenario.close()
    }
}
