package app.nastavnik

import android.graphics.Bitmap
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.InputDevice
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
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
        return "@@ тема Исследование функции\n@@ ok $spec\n" +
            "Верно! Экстремумы найдены правильно.\n@@ подробнее\n" +
            "Производная \$f'(x) = 3x^2 - 3\$ найдена правильно, и точки \$x = \\pm 1\$ тоже.\n\n" +
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
                    val text = when {
                        body.contains("тренировочный вариант из") -> VARIANT_JSON
                        body.contains("Составь мини-тест") -> QUIZ_JSON
                        body.contains("повторять материал по карточкам") -> "{\"q\":\"Чему равна производная \$x^3\$?\",\"a\":\"\$3x^2\$\"}"
                        else -> "Вот чистовик:\n```json\n$note\n```"
                    }
                    val resp = buildJsonObject {
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                        put("stop_reason", "end_turn")
                    }
                    MockResponse().setHeader("content-type", "application/json").setBody(resp.toString())
                }
            }
        }
        server.start()
        device.executeShellCommand("settings put global hide_error_dialogs 1")
        dismissSystemAnr()
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
        s.setEditorTipsShown()
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
        // Экран в одну колонку: строка с заголовком не сливается с соседней колонкой.
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_HISTORY),
        )
        SystemClock.sleep(800)
        scenario.onActivity { BubbleService.start(it) }
        target = device.wait(Until.findObject(By.text("История")), 5000)?.visibleBounds
        assertNotNull("нет заголовка «История»", target)
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

        // Видна короткая подсказка; «Подробнее» раскрывает полную — заметка становится шире.
        val narrow = AnswerOverlay.shownNotes.single()
        device.findObject(By.textStartsWith("Подробнее"))!!.click()
        SystemClock.sleep(1500)
        screenshot("note_expanded")
        val wide = AnswerOverlay.shownNotes.single()
        assertTrue("заметка не раскрылась: $narrow -> $wide", wide.width >= narrow.width && wide.height > narrow.height)
        device.findObject(By.textStartsWith("Коротко"))!!.click()
        SystemClock.sleep(800)

        // «Глазик» на заметке прячет выделение и показывает снова.
        device.findObject(By.desc("Скрыть выделение"))!!.click()
        SystemClock.sleep(500)
        assertTrue("выделение не скрылось", AnswerOverlay.shownMarks.isEmpty())
        screenshot("mark_hidden")
        device.wait(Until.findObject(By.desc("Показать выделение")), 3000)!!.click()
        SystemClock.sleep(500)
        assertEquals("выделение не вернулось", 1, AnswerOverlay.shownMarks.size)

        // Первый запрос: стрим, картинка и размер снимка в последнем сообщении, промт с координатами.
        val first = Json.parseToJsonElement(bodies.first()).jsonObject
        val msgs = first["messages"]!!.jsonArray
        val lastContent = msgs.last().jsonObject["content"] as JsonArray
        assertEquals("image", (lastContent[0].jsonObject["type"] as JsonPrimitive).content)
        // Размер снимка всегда в тексте; если запомнены другие страницы конспекта — с пояснением про них.
        assertTrue(bodies.first().contains("[Снимок экрана:") || bodies.first().contains("Последний снимок — текущий экран"))
        val system = (first["system"] as JsonPrimitive).content
        assertTrue(system.contains("Ты — наставник"))
        assertTrue(system.contains("в пикселях"))
        // Тема из ответа становится названием задачи в истории.
        val conv = runBlocking { ctx.app.db.conversations().get(ctx.app.settings.load().activeConversationId) }
        assertEquals("задача не названа по теме", "Исследование функции", conv?.title)

        // Чистовик: снимок → «Собрать» → предпросмотр открывается сам.
        cleanBtn.click()
        val collect = device.wait(Until.findObject(By.text("Собрать")), 30000)
        if (collect == null) screenshot("no_collect")
        collect!!.click()
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
        // Карточки из чистовика попали в повторение.
        val cardCount = runBlocking { ctx.app.db.cards().observeForNote(n.id).first() }
        assertEquals("карточки не добавились", 5, cardCount)
        device.pressBack()
        scenario.close()
    }

    /** Прокрутить к тексту средствами UiScrollable (надёжно на длинных экранах) и проверить, что он есть. */
    private fun scrollToText(text: String): Boolean {
        runCatching {
            androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).apply { maxSearchSwipes = 40 }
                .scrollTextIntoView(text)
        }
        if (device.findObject(androidx.test.uiautomator.UiSelector().textContains(text)).exists()) return true
        return findScrolling(By.textContains(text), 8000) != null
    }

    /** Найти элемент, прокручивая экран вниз: длинные экраны не помещаются целиком. */
    /** Сначала подождать на экране (без прокрутки, которая сбивает подгрузку), потом искать прокруткой. */
    private fun findHere(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2? =
        device.wait(Until.findObject(selector), 15000) ?: findScrolling(selector, 15000)

    private fun findScrolling(selector: androidx.test.uiautomator.BySelector, timeoutMs: Long = 20000): androidx.test.uiautomator.UiObject2? {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            device.wait(Until.findObject(selector), 800)?.let { return it }
            val w = device.displayWidth
            val h = device.displayHeight
            // Медленный жест у правого края карточек: без инерции и мимо полей ввода.
            device.swipe((w * 0.7).toInt(), (h * 0.8).toInt(), (w * 0.7).toInt(), (h * 0.3).toInt(), 60)
        }
        screenshot("not_found_" + selector.toString().filter { it.isLetterOrDigit() }.take(24))
        // Что видит UiAutomator (тексты в дереве окна) — понять, почему не нашёл видимое.
        runCatching {
            val out = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(out)
            Regex("""text="([^"]+)"""").findAll(out.toString()).map { it.groupValues[1] }.toList().let { println("TREEDIAG ${it.size}: ${it.take(60)}") }
        }
        return null
    }

    /** Штрих пером: вниз, несколько точек, вверх — как настоящий стилус. */
    private fun penStroke(points: List<Pair<Float, Float>>) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val src = InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN
        val down = SystemClock.uptimeMillis()
        points.forEachIndexed { i, (x, y) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.6f; size = 1f })
            val ev = MotionEvent.obtain(down, down + i * 12L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
            instr.uiAutomation.injectInputEvent(ev, true)
            ev.recycle()
        }
    }

    /** Тетрадь в приложении: пишем пером, спрашиваем наставника — отметка на листе; потом фото и PDF становятся тетрадями. */
    @Test
    fun notebookWriteAskAndImport() {
        target = null
        val nbs = ctx.app.notebooks
        val (meta, _) = nbs.store.create("Тест тетради", app.nastavnik.ink.Paper.GRID)
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_NOTEBOOK)
                .putExtra(MainActivity.EXTRA_NOTEBOOK, meta.id),
        )
        val sheet = device.wait(Until.findObject(By.desc("Лист тетради")), 10000)
        if (sheet == null) screenshot("notebook_missing")
        assertNotNull("лист тетради не открылся", sheet)
        SystemClock.sleep(1500)
        dismissSystemAnr()
        val b = sheet!!.visibleBounds
        // Пишем «пером» три строки: что-то вроде уравнения и решения.
        for (row in 0 until 3) {
            val y = b.top + b.height() * (0.18f + row * 0.09f)
            val x0 = b.left + b.width() * 0.12f
            penStroke((0..24).map { k -> (x0 + k * b.width() * 0.018f) to (y + (if (k % 2 == 0) -8f else 8f)) })
            penStroke(listOf(x0 + b.width() * 0.5f to y - 20f, x0 + b.width() * 0.5f to y + 20f))
        }
        SystemClock.sleep(2500)
        val saved = nbs.store.loadPages(nbs.store.meta(meta.id)!!).first()
        assertTrue("штрихи не сохранились (${saved.strokes.size})", saved.strokes.size >= 6)
        screenshot("notebook_written")

        // Наставник: отметка на листе и заметка в панели.
        device.wait(Until.findObject(By.desc("Подсказка")), 5000)!!.click()
        val until = SystemClock.uptimeMillis() + 40000
        while (app.nastavnik.ink.InkView.shownMarks.isEmpty() && SystemClock.uptimeMillis() < until) SystemClock.sleep(300)
        SystemClock.sleep(1500)
        screenshot("notebook_answer")
        assertEquals("нет отметки на листе", 1, app.nastavnik.ink.InkView.shownMarks.size)
        // Пока на экране ответ, в панели только он: плиток вопросов нет, есть «К вопросам».
        assertNotNull("нет кнопки «К вопросам»", device.wait(Until.findObject(By.desc("К вопросам")), 5000))
        assertTrue("в режиме ответа видны плитки вопросов", !device.hasObject(By.desc("Где ошибка?")))
        val req = bodies.last { it.contains("\"stream\":true") }
        assertTrue("вопрос ушёл без снимка страницы", req.contains("\"type\":\"image\""))

        // «В карточки +» под заметкой — карточка сразу в повторении.
        val cardsBefore = runBlocking { ctx.app.db.cards().observeCount().first() }
        device.wait(Until.findObject(By.text("В карточки +")), 5000)!!.click()
        assertNotNull("карточка не добавилась", device.wait(Until.findObject(By.text("✓ В повторении")), 15000))
        assertEquals(cardsBefore + 1, runBlocking { ctx.app.db.cards().observeCount().first() })
        // «К вопросам» — плитки возвращаются, последний ответ остаётся под рукой.
        device.findObject(By.desc("К вопросам"))!!.click()
        assertNotNull("плитки не вернулись", device.wait(Until.findObject(By.desc("Где ошибка?")), 5000))
        assertNotNull(device.wait(Until.findObject(By.text("Последний ответ")), 3000))
        SystemClock.sleep(600)
        screenshot("notebook_ask")
        scenario.close()

        // Фото тетради с бумаги → новая тетрадь.
        val photo = File(ctx.cacheDir, "paper.jpg")
        val bmp = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply {
            val c = android.graphics.Canvas(this)
            c.drawColor(android.graphics.Color.rgb(246, 244, 238))
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(30, 50, 140); textSize = 64f }
            c.drawText("x² − 5x + 6 = 0", 120f, 300f, p)
            c.drawText("D = 25 − 24 = 1", 120f, 420f, p)
        }
        photo.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val fromPhoto = runBlocking { nbs.importImages(listOf(android.net.Uri.fromFile(photo))) }
        assertNotNull("фото не стало тетрадью", fromPhoto)
        assertEquals(1, fromPhoto!!.pages.size)

        // PDF из двух страниц → тетрадь из двух страниц.
        val pdfFile = File(ctx.cacheDir, "lecture.pdf")
        android.graphics.pdf.PdfDocument().apply {
            repeat(2) { i ->
                val pg = startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                pg.canvas.drawText("Лекция, страница ${i + 1}", 60f, 80f, android.graphics.Paint().apply { textSize = 24f })
                finishPage(pg)
            }
            pdfFile.outputStream().use { writeTo(it) }
            close()
        }
        val fromPdf = runBlocking { nbs.importPdf(android.net.Uri.fromFile(pdfFile)) }
        assertNotNull("PDF не стал тетрадью", fromPdf)
        assertEquals(2, fromPdf!!.pages.size)

        val photoScenario = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_NOTEBOOK)
                .putExtra(MainActivity.EXTRA_NOTEBOOK, fromPhoto.id),
        )
        SystemClock.sleep(3000)
        screenshot("notebook_photo")
        photoScenario.close()

        val list = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_NOTEBOOKS),
        )
        SystemClock.sleep(2500)
        screenshot("screen_notebooks")
        list.close()
    }

    /** Тренировка: вариант → решать в тетради (наставник знает ответы), мини-тест с разбором, экзамены. */
    @Test
    fun trainingVariantQuizAndExams() {
        val study = ctx.app.study
        study.saveExams(emptyList())
        val launch = { screen: String ->
            ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, screen))
        }
        // Вариант.
        var sc = launch(MainActivity.SCREEN_TRAINING)
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8000)!!.text = "Пределы"
        device.findObject(By.text("Составить вариант"))!!.click()
        assertNotNull("вариант не составился", findScrolling(By.text("Показать ответы")))
        SystemClock.sleep(2500)
        screenshot("training_variant")
        assertTrue(bodies.last().contains("Тема: Пределы"))
        device.findObject(By.text("Показать ответы"))!!.click()
        assertNotNull(findScrolling(By.textContains("Ответ: 1"), 10000))
        findScrolling(By.textContains("Решать в тетради"), 20000)!!.click()
        assertNotNull("тетрадь варианта не открылась", device.wait(Until.findObject(By.desc("Лист тетради")), 10000))
        assertNotNull("нет условий варианта в панели", device.wait(Until.findObject(By.textStartsWith("Вариант · 3")), 5000))
        val nb = ctx.app.notebooks.store.list().first()
        assertEquals(3, nb.tasks.size)
        SystemClock.sleep(2000)
        screenshot("notebook_variant")
        sc.close()

        // Мини-тест: первый ответ неверный, второй верный → 1 из 2, ошибка уходит в карточки.
        sc = launch(MainActivity.SCREEN_TRAINING)
        device.wait(Until.findObject(By.text("Мини-тест")), 8000)!!.click()
        device.wait(Until.findObject(By.text("Начать тест")), 5000)!!.click()
        // Варианты рисуются формулами (WebView) — их текст виден не сразу; ждём на месте, листаем только если нет.
        findHere(By.text("Ничего"))!!.click()
        SystemClock.sleep(800)
        screenshot("quiz_answer")
        findHere(By.text("Дальше"))!!.click()
        findHere(By.text("Матрица"))!!.click()
        findHere(By.text("Итог"))!!.click()
        // Итог появляется с анимацией — на медленном эмуляторе бывает дольше 6 секунд.
        assertNotNull("нет итога теста", findScrolling(By.text("1 из 2"), 15000))
        val before = runBlocking { ctx.app.db.cards().observeCount().first() }
        // Кнопка на экране сразу под итогом — ждём на месте (листание тут ни к чему), потом уже листаем.
        val toCards = device.wait(Until.findObject(By.textStartsWith("Ошибки в карточки")), 8000) ?: findScrolling(By.textStartsWith("Ошибки в карточки"), 10000)
        assertNotNull("нет кнопки «Ошибки в карточки»", toCards)
        assertEquals("Ошибки в карточки (1)", toCards!!.text)
        toCards.click()
        assertNotNull(findScrolling(By.text("Повторить карточки"), 6000))
        assertEquals(before + 1, runBlocking { ctx.app.db.cards().observeCount().first() })
        screenshot("quiz_result")
        sc.close()

        // Экзамены: список с обратным отсчётом и плашка на главном.
        val day = 24 * 3600 * 1000L
        study.saveExams(listOf(app.nastavnik.study.Exam("Математический анализ", System.currentTimeMillis() + 12 * day)))
        sc = launch(MainActivity.SCREEN_EXAMS)
        assertNotNull(findScrolling(By.text("Готовиться"), 10000))
        SystemClock.sleep(1000)
        screenshot("training_exams")
        sc.close()
        sc = launch(MainActivity.SCREEN_HOME)
        // Плитка экзамена на главной: крупно — сколько дней, подпись — предмет.
        assertNotNull("нет обратного отсчёта на главном", findScrolling(By.text("Математический анализ"), 20000))
        assertNotNull("нет числа дней до экзамена", device.findObject(By.textEndsWith("дней")) ?: device.findObject(By.textEndsWith("дня")))
        SystemClock.sleep(800)
        screenshot("home_exam")
        sc.close()
        study.saveExams(emptyList())
    }

    /** Конспекты: поиск находит чистовик, экспорт в три формата, шпаргалка открывается в предпросмотре. */
    @Test
    fun searchExportAndCheatSheet() {
        val raw = ctx.assets.open("sample_note.json").bufferedReader().use { it.readText() }
        val note = app.nastavnik.notes.CleanNoteParser.parse(raw)
        val json = app.nastavnik.notes.CleanNoteParser.toJson(note)
        val id = runBlocking {
            ctx.app.db.cleanNotes().insert(
                app.nastavnik.data.CleanNoteEntity(
                    conversationId = null, subject = note.subject, title = "Исследование функции", json = json,
                    pdfPath = null, pngPaths = null, publicPath = null, createdAt = System.currentTimeMillis(),
                ),
            )
        }
        try {
            // Экспорт: Markdown с формулами, LaTeX с окружениями, Word — zip.
            val entity = runBlocking { ctx.app.db.cleanNotes().get(id)!! }
            val md = runBlocking { app.nastavnik.ui.exportNote(ctx, entity, app.nastavnik.ui.ExportFormat.MARKDOWN) }
            assertTrue(md.readText().contains("# Исследование функции"))
            val tex = runBlocking { app.nastavnik.ui.exportNote(ctx, entity, app.nastavnik.ui.ExportFormat.LATEX) }
            assertTrue(tex.readText().contains("\\begin{document}"))
            val docx = runBlocking { app.nastavnik.ui.exportNote(ctx, entity, app.nastavnik.ui.ExportFormat.WORD) }
            assertEquals('P'.code.toByte(), docx.readBytes()[0])

            // Поиск по слову из конспекта.
            var sc = ActivityScenario.launch<MainActivity>(
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_SEARCH),
            )
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8000)!!.text = "экстремум"
            assertNotNull("поиск не нашёл чистовик", device.wait(Until.findObject(By.textStartsWith("ЧИСТОВИКИ")), 8000))
            SystemClock.sleep(800)
            screenshot("search_results")
            sc.close()

            // Шпаргалка из чистовиков → предпросмотр.
            sc = ActivityScenario.launch<MainActivity>(
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_HISTORY),
            )
            device.wait(Until.findObject(By.text("Чистовики")), 8000)!!.click()
            findScrolling(By.text("Собрать шпаргалку"), 8000)!!.click()
            assertNotNull("шпаргалка не открылась", findScrolling(By.text("Сохранить"), 10000))
            SystemClock.sleep(3000)
            screenshot("cheat_sheet")
            val state = ctx.app.clean.state.value as app.nastavnik.session.CleanState.Preview
            assertTrue(state.note.title.startsWith("Шпаргалка"))
            ctx.app.clean.cancel()
            sc.close()
        } finally {
            runBlocking { ctx.app.db.cleanNotes().delete(id) }
        }
    }

    /** Удобство: расходы считаются, лимит останавливает запросы, копия сохраняется, тёмная тема, виджет. */
    @Test
    fun usageBackupDarkThemeAndWidget() {
        val na = ctx.app
        // Расходы: каждый запрос к нейросети попадает в журнал; дневной лимит останавливает следующие.
        na.settings.setUsageJson("[]")
        val req = app.nastavnik.ai.ChatRequest("claude-sonnet-5-5", "s", listOf(app.nastavnik.ai.ChatTurn(app.nastavnik.ai.Role.USER, "Составь мини-тест")), maxTokens = 100)
        runBlocking { na.ai().complete("sk-ant-test", req) }
        assertEquals(1, na.usage.today().requests)
        na.settings.setDailyLimitUsd(0.000001f)
        val blocked = runCatching { runBlocking { na.ai().complete("sk-ant-test", req) } }.exceptionOrNull()
        assertEquals(app.nastavnik.ai.AiException.Kind.BUDGET, (blocked as? app.nastavnik.ai.AiException)?.kind)
        na.settings.setDailyLimitUsd(0f)

        // Резервная копия: архив с базой и тетрадями, без ключей.
        val zip = File(ctx.cacheDir, "backup.zip")
        val count = runBlocking { zip.outputStream().use { app.nastavnik.data.Backup.write(na, it) } }
        assertTrue("в копии мало файлов: $count", count >= 1)
        assertTrue(zip.inputStream().use { app.nastavnik.data.Backup.looksValid(it) })
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.any { it.startsWith("db/") })
        assertTrue(names.none { it.contains("secure") })

        // Тёмная тема: главный экран и настройки.
        na.settings.setThemeMode("DARK")
        try {
            var sc = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_HOME))
            SystemClock.sleep(2000)
            screenshot("home_dark")
            sc.close()
            sc = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_SETTINGS))
            // Длинный экран настроек: UiAutomator иногда не видит в дереве доступности текст, который уже на экране,
            // поэтому здесь только листаем и снимаем экран; расходы и копия проверены выше напрямую.
            scrollToText("Дневной лимит")
            SystemClock.sleep(800)
            screenshot("settings_usage_dark")
            // Подписи разделов — обычный текст: их UiAutomator находит надёжнее, чем кнопки со значком.
            scrollToText("Дополнительно")
            runCatching { device.findObject(androidx.test.uiautomator.UiSelector().textContains("Дополнительно")).click() }
            SystemClock.sleep(600)
            scrollToText("Все записи в одном файле")
            scrollToText("Новые сборки выходят на GitHub")
            SystemClock.sleep(800)
            screenshot("settings_backup_dark")
            sc.close()

            // Тетрадь в тёмной теме: тёмный стол и бумага, светлые чернила; пишем букву с разворотом пера.
            val (nb, _) = ctx.app.notebooks.store.create("Тёмная тетрадь", app.nastavnik.ink.Paper.LINED)
            sc = ActivityScenario.launch<MainActivity>(
                Intent(ctx, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_NOTEBOOK)
                    .putExtra(MainActivity.EXTRA_NOTEBOOK, nb.id),
            )
            val sheet = device.wait(Until.findObject(By.desc("Лист тетради")), 10000)!!.visibleBounds
            for (row in 0 until 3) {
                val x0 = sheet.left + sheet.width() * 0.12f
                val y0 = sheet.top + sheet.height() * (0.2f + row * 0.1f)
                // «и»: вниз, вверх по той же линии, вниз по диагонали, вверх и вниз.
                penStroke((0..10).map { k -> x0 to y0 + k * 6f } + (10 downTo 0).map { k -> (x0 + 1f) to y0 + k * 6f } +
                    (0..10).map { k -> (x0 + k * 4f) to y0 + k * 6f } + (10 downTo 0).map { k -> (x0 + 40f) to y0 + k * 6f } +
                    (0..10).map { k -> (x0 + 40f) to y0 + k * 6f })
            }
            SystemClock.sleep(1500)
            screenshot("notebook_dark")
            sc.close()
        } finally {
            na.settings.setThemeMode("LIGHT")
        }

        // Виджет: разметка собирается и заполняется без ошибок.
        val views = android.widget.RemoteViews(ctx.packageName, R.layout.widget_study)
        val host = android.widget.FrameLayout(ctx)
        val v = views.apply(ctx, host)
        assertNotNull(v.findViewById<android.widget.TextView>(R.id.widget_due))
    }

    /** Одно событие пера (или пальца) с заданными временами. */
    private fun touch(action: Int, x: Float, y: Float, down: Long, at: Long, finger: Boolean = false) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = if (finger) MotionEvent.TOOL_TYPE_FINGER else MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.6f; size = 1f })
        val src = if (finger) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN
        val ev = MotionEvent.obtain(down, at, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
        instr.uiAutomation.injectInputEvent(ev, true)
        ev.recycle()
    }

    /** Касание двумя пальцами (без движения) — «Отменить». */
    private fun twoFingerTap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        fun ev(action: Int, n: Int): MotionEvent {
            val props = Array(n) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(n) { i -> MotionEvent.PointerCoords().apply { this.x = x + i * 120f; this.y = y; pressure = 1f; size = 1f } }
            return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, n, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        listOf(
            ev(MotionEvent.ACTION_DOWN, 1),
            ev(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2),
            ev(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2),
            ev(MotionEvent.ACTION_UP, 1),
        ).forEach { instr.uiAutomation.injectInputEvent(it, true); it.recycle(); SystemClock.sleep(20) }
    }

    /** Редактор: все инструменты и жесты пером, как у человека. */
    /** Кнопка «Наставник» всегда доступна и открывает панель по нажатию. */
    @Test
    fun notebookMentorButton() {
        val nbs = ctx.app.notebooks
        val (meta, _) = nbs.store.create("Кнопка", app.nastavnik.ink.Paper.GRID)
        ctx.app.settings.setEditorTipsShown()
        val sc = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_NOTEBOOK)
                .putExtra(MainActivity.EXTRA_NOTEBOOK, meta.id),
        )
        try {
            val showBtn = device.wait(Until.findObject(By.desc("Показать наставника")), 4000)
            if (showBtn != null) {
                showBtn.click()
                assertNotNull("панель не открылась", device.wait(Until.findObject(By.desc("Скрыть наставника")), 5000))
                device.findObject(By.desc("Скрыть наставника"))?.click()
                assertNotNull("кнопка пропала", device.wait(Until.findObject(By.desc("Показать наставника")), 5000))
            } else {
                val hideBtn = device.wait(Until.findObject(By.desc("Скрыть наставника")), 4000)
                hideBtn?.click()
                assertNotNull("панель не закрылась", device.wait(Until.findObject(By.desc("Показать наставника")), 5000))
                device.findObject(By.desc("Показать наставника"))?.click()
                assertNotNull("панель не открылась", device.wait(Until.findObject(By.desc("Скрыть наставника")), 5000))
            }
        } finally {
            sc.close()
            nbs.store.delete(meta.id)
        }
    }

    @Test
    fun editorToolsAndGestures() {
        val nbs = ctx.app.notebooks
        val (meta, _) = nbs.store.create("Редактор", app.nastavnik.ink.Paper.GRID)
        // Подсказки по жестам показываются при первом открытии.
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().remove("editor_tips_shown").commit()
        val sc = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_NOTEBOOK)
                .putExtra(MainActivity.EXTRA_NOTEBOOK, meta.id),
        )
        assertNotNull("нет подсказок по жестам", device.wait(Until.findObject(By.text("Как устроен лист")), 10000))
        SystemClock.sleep(600)
        screenshot("editor_tips")
        device.findObject(By.text("Понятно"))!!.click()
        val sheet = device.wait(Until.findObject(By.desc("Лист тетради")), 10000)!!.visibleBounds
        SystemClock.sleep(800)
        fun page() = run { SystemClock.sleep(1600); nbs.store.loadPages(nbs.store.meta(meta.id)!!).first() }
        val x0 = sheet.left + sheet.width() * 0.15f
        val y0 = sheet.top + sheet.height() * 0.2f

        // 1. Ручка.
        penStroke((0..30).map { k -> (x0 + k * 10f) to (y0 + (k % 5) * 3f) })
        assertEquals(1, page().strokes.size)

        // 2. Задержал перо на месте — ластик, пока держишь; стёр линию; отпустил — снова ручка.
        var down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x0 + 150f, y0 + 6f, down, down)
        SystemClock.sleep(1300)
        assertTrue("временный ластик не включился", app.nastavnik.ink.InkView.debugTempEraser)
        screenshot("editor_hold_eraser")
        for (k in 0..10) touch(MotionEvent.ACTION_MOVE, x0 + 100f + k * 10f, y0 + 6f, down, SystemClock.uptimeMillis())
        touch(MotionEvent.ACTION_UP, x0 + 200f, y0 + 6f, down, SystemClock.uptimeMillis())
        assertEquals("перо-ластик не стёр линию", 0, page().strokes.size)

        // 3. Нарисовал прямоугольник и задержал перо — он стал ровным.
        down = SystemClock.uptimeMillis()
        val rect = listOf(0f to 0f, 300f to 4f, 302f to 200f, 2f to 198f, 3f to 3f)
        var t = down
        touch(MotionEvent.ACTION_DOWN, x0, y0 + 80f, down, t)
        for (i in 0 until rect.size - 1) {
            val (ax, ay) = rect[i]; val (bx, by) = rect[i + 1]
            for (k in 1..20) {
                t += 8
                touch(MotionEvent.ACTION_MOVE, x0 + ax + (bx - ax) * k / 20f, y0 + 80f + ay + (by - ay) * k / 20f, down, t)
            }
        }
        SystemClock.sleep(900)
        SystemClock.sleep(400)
        screenshot("editor_snap")
        touch(MotionEvent.ACTION_UP, x0 + 3f, y0 + 83f, down, SystemClock.uptimeMillis())
        val snapped = page().strokes
        assertEquals(1, snapped.size)
        assertEquals("фигура не выровнялась", app.nastavnik.ink.Tool.SHAPE, snapped.single().tool)

        // 4. Лассо: обвёл прямоугольник — выделен; перетащил; удалил из меню.
        device.findObject(By.desc("Лассо"))!!.click()
        SystemClock.sleep(400)
        penStroke(listOf(x0 - 30f to y0 + 50f, x0 + 340f to y0 + 50f, x0 + 340f to y0 + 310f, x0 - 30f to y0 + 310f, x0 - 30f to y0 + 50f).let { pts ->
            pts.zipWithNext().flatMap { (a, b) -> (0..12).map { k -> (a.first + (b.first - a.first) * k / 12f) to (a.second + (b.second - a.second) * k / 12f) } }
        })
        SystemClock.sleep(500)
        assertEquals("лассо ничего не выделило", 1, app.nastavnik.ink.InkView.debugSelectionCount)
        assertNotNull("нет меню выделения", device.wait(Until.findObject(By.desc("Удалить")), 3000))
        screenshot("editor_lasso")
        val before = page().strokes.single().x(0)
        penStroke((0..15).map { k -> (x0 + 150f + k * 8f) to (y0 + 180f) })
        val after = page().strokes.single().x(0)
        assertTrue("выделение не сдвинулось: $before -> $after", after > before + 30f)
        device.findObject(By.desc("Удалить"))!!.click()
        assertEquals("не удалилось", 0, page().strokes.size)

        // 5. Фигуры: овал.
        device.findObject(By.desc("Фигуры"))!!.click()
        SystemClock.sleep(300)
        // Вид фигуры и цвет — во всплывающем окне у плашки, по кружку «Настройки инструмента».
        device.findObject(By.desc("Настройки инструмента"))!!.click()
        var oval = device.wait(Until.findObject(By.desc("Овал")), 6000)
        if (oval == null) {
            screenshot("no_oval")
            device.findObject(By.desc("Настройки инструмента"))?.click()
            oval = device.wait(Until.findObject(By.desc("Овал")), 6000)
        }
        screenshot("editor_popover")
        oval!!.click()
        SystemClock.sleep(400)
        penStroke((0..20).map { k -> (x0 + k * 12f) to (y0 + 120f + k * 8f) })
        assertEquals(app.nastavnik.ink.Tool.SHAPE, page().strokes.single().tool)
        screenshot("editor_shapes")

        // 6. Текст: коснулся листа — ввёл — «Готово».
        device.findObject(By.desc("Текст"))!!.click()
        SystemClock.sleep(300)
        down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x0, y0 + 420f, down, down)
        touch(MotionEvent.ACTION_UP, x0, y0 + 420f, down, down + 60)
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)!!.text = "Дано: f(x) = x² − 3x"
        device.findObject(By.text("Готово"))!!.click()
        assertEquals("текст не напечатался", "Дано: f(x) = x² − 3x", page().texts.singleOrNull()?.text)
        screenshot("editor_text")

        // 7. Частичный ластик режет линию на две.
        device.findObject(By.desc("Ручка"))!!.click()
        penStroke((0..40).map { k -> (x0 + k * 10f) to (y0 + 560f) })
        device.findObject(By.desc("Ластик"))!!.click()
        SystemClock.sleep(600)
        val opt = device.wait(Until.findObject(By.desc("Настройки инструмента")), 5000)
        opt?.click()
        val part = device.wait(Until.findObject(By.text("Часть линии")), 5000) ?: run {
            opt?.click()
            device.wait(Until.findObject(By.text("Часть линии")), 5000)
        }
        part?.click()
        SystemClock.sleep(400)
        penStroke((0..10).map { k -> (x0 + 200f) to (y0 + 520f + k * 8f) })
        assertEquals("частичный ластик не разрезал линию", 3, page().strokes.size)

        // 8. Два пальца — «Отменить»: разрезанная линия снова целая.
        twoFingerTap(sheet.centerX() - 60f, sheet.centerY().toFloat())
        assertEquals("касание двумя пальцами не отменило", 2, page().strokes.size)

        // 9. Обзор страниц.
        device.findObject(By.desc("Все страницы"))!!.click()
        assertNotNull(device.wait(Until.findObject(By.text("Страницы")), 5000))
        SystemClock.sleep(1200)
        screenshot("pages_overview")
        device.findObject(By.text("Копия"))!!.click()
        SystemClock.sleep(1500)
        assertEquals("страница не скопировалась", 2, nbs.store.meta(meta.id)!!.pages.size)
        device.pressBack()
        SystemClock.sleep(500)
        screenshot("editor_toolbar")
        sc.close()
    }

    /** Оформление: темы применяются сразу, а лист тетради в любой теме белый. */
    @Test
    fun themesApplyAndSheetStaysWhite() {
        val st = ctx.app.settings
        try {
            var sc = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_APPEARANCE))
            assertNotNull("нет экрана оформления", device.wait(Until.findObject(By.desc("Тема Океан")), 8000))
            SystemClock.sleep(800)
            screenshot("appearance_light")
            device.findObject(By.desc("Тема Океан"))!!.click()
            SystemClock.sleep(900)
            assertEquals("ocean", st.load().lightTheme)
            screenshot("appearance_ocean")
            // Тёмная тема из галереи сразу включает тёмный режим.
            findScrolling(By.desc("Тема Полночь"), 10000)!!.click()
            SystemClock.sleep(900)
            assertEquals("DARK", st.load().themeMode)
            assertEquals("midnight", st.load().darkTheme)
            screenshot("appearance_midnight")
            sc.close()

            sc = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_HOME))
            SystemClock.sleep(1500)
            screenshot("home_midnight")
            sc.close()

            // Тетрадь в тёмной теме: стол тёмный, лист — белый.
            val (nb, _) = ctx.app.notebooks.store.create("Белый лист", app.nastavnik.ink.Paper.BLANK)
            sc = ActivityScenario.launch<MainActivity>(
                Intent(ctx, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_NOTEBOOK)
                    .putExtra(MainActivity.EXTRA_NOTEBOOK, nb.id),
            )
            val sheet = device.wait(Until.findObject(By.desc("Лист тетради")), 10000)!!.visibleBounds
            SystemClock.sleep(1500)
            screenshot("notebook_midnight")
            val f = File(ctx.cacheDir, "sheet.png")
            assertTrue(device.takeScreenshot(f))
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            val px = bmp.getPixel(sheet.left + sheet.width() / 3, sheet.top + sheet.height() / 3)
            val r = android.graphics.Color.red(px); val g = android.graphics.Color.green(px); val b = android.graphics.Color.blue(px)
            assertTrue("лист не белый в тёмной теме: $r,$g,$b", r > 235 && g > 235 && b > 235)
            val desk = bmp.getPixel(sheet.left + 4, sheet.top + 4)
            assertTrue("стол не тёмный", android.graphics.Color.red(desk) < 90)
            sc.close()
        } finally {
            st.setThemeMode("LIGHT"); st.setLightTheme("paper"); st.setDarkTheme("graphite"); st.setAccent(0)
        }
    }

    /** Наставник видит весь конспект: пролистанные страницы уходят вместе со следующим вопросом. */
    @Test
    fun questionCarriesWholeNotebook() {
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_SETTINGS),
        )
        SystemClock.sleep(1200)
        scenario.onActivity { BubbleService.start(it) }
        SystemClock.sleep(800)
        askHint()
        assertNotNull("нет заметки", device.wait(Until.findObject(By.desc("Заметка: Верно")), 40000))
        val afterAsk = BubbleService.notebookPages
        assertTrue("страница вопроса не запомнилась", afterAsk >= 1)
        // Убираем заметку и спокойно листаем вниз, задерживаясь на каждом экране.
        device.findObject(By.desc("Закрыть заметку"))?.click()
        val x = device.displayWidth / 4
        val h = device.displayHeight
        repeat(2) {
            device.swipe(x, h * 3 / 4, x, h / 4, 60)
            SystemClock.sleep(6000)
        }
        val afterScroll = BubbleService.notebookPages
        Log.i("MentorFlowTest", "notebook pages: after ask $afterAsk, after scroll $afterScroll")
        assertTrue("пролистанные страницы не запомнились ($afterAsk -> $afterScroll)", afterScroll > afterAsk)

        val before = bodies.size
        askHint()
        assertNotNull("нет второй заметки", device.wait(Until.findObject(By.desc("Заметка: Верно")), 40000))
        val req = Json.parseToJsonElement(bodies.drop(before).first { it.contains("\"stream\":true") }).jsonObject
        val content = req["messages"]!!.jsonArray.last().jsonObject["content"] as JsonArray
        val images = content.count { (it.jsonObject["type"] as JsonPrimitive).content == "image" }
        val text = content.last().jsonObject["text"]!!.jsonPrimitive.content
        Log.i("MentorFlowTest", "second ask: $images images")
        assertTrue("с вопросом ушёл только текущий экран ($images)", images >= 2)
        assertTrue("нет пояснения про конспект", text.contains("Последний снимок — текущий экран"))
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

    private companion object {
        const val VARIANT_JSON = "[{\"task\":\"Найдите \$\\\\lim_{x\\\\to 0} \\\\frac{\\\\sin 3x}{x}\$\",\"answer\":\"3\",\"level\":1}," +
            "{\"task\":\"Найдите предел последовательности (n+1)/n\",\"answer\":\"1\",\"level\":2}," +
            "{\"task\":\"Существует ли предел sin(1/x) при x→0? Обоснуйте.\",\"answer\":\"нет\",\"level\":3}]"
        const val QUIZ_JSON = "[{\"q\":\"Что такое предел функции?\",\"options\":[\"Ничего\",\"Число, к которому стремятся значения\",\"Максимум\",\"Производная\"],\"correct\":1,\"why\":\"По определению Коши.\"}," +
            "{\"q\":\"Что можно умножать на вектор слева?\",\"options\":[\"Матрица\",\"Ничто\",\"Только число\",\"Только вектор\"],\"correct\":0,\"why\":\"Матрица подходящего размера.\"}]"
    }
}
