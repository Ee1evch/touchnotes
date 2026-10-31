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
        // Карточки из чистовика попали в повторение.
        val cardCount = runBlocking { ctx.app.db.cards().observeForNote(n.id).first() }
        assertEquals("карточки не добавились", 5, cardCount)
        device.pressBack()
        scenario.close()
    }

    /** Найти элемент, прокручивая экран вниз: длинные экраны не помещаются целиком. */
    private fun findScrolling(selector: androidx.test.uiautomator.BySelector, timeoutMs: Long = 20000): androidx.test.uiautomator.UiObject2? {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            device.wait(Until.findObject(selector), 1500)?.let { return it }
            val w = device.displayWidth
            val h = device.displayHeight
            device.swipe(w / 2, (h * 0.75).toInt(), w / 2, (h * 0.35).toInt(), 25)
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
        device.wait(Until.findObject(By.text("Подсказка")), 5000)!!.click()
        val until = SystemClock.uptimeMillis() + 40000
        while (app.nastavnik.ink.InkView.shownMarks.isEmpty() && SystemClock.uptimeMillis() < until) SystemClock.sleep(300)
        SystemClock.sleep(1500)
        screenshot("notebook_answer")
        assertEquals("нет отметки на листе", 1, app.nastavnik.ink.InkView.shownMarks.size)
        val req = bodies.last { it.contains("\"stream\":true") }
        assertTrue("вопрос ушёл без снимка страницы", req.contains("\"type\":\"image\""))

        // «В карточки +» под заметкой — карточка сразу в повторении.
        val cardsBefore = runBlocking { ctx.app.db.cards().observeCount().first() }
        device.wait(Until.findObject(By.text("В карточки +")), 5000)!!.click()
        assertNotNull("карточка не добавилась", device.wait(Until.findObject(By.text("✓ В повторении")), 15000))
        assertEquals(cardsBefore + 1, runBlocking { ctx.app.db.cards().observeCount().first() })
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
        assertNotNull(findScrolling(By.textContains("Ответ: 1"), 6000))
        findScrolling(By.text("Решать в тетради"), 6000)!!.click()
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
        findScrolling(By.text("Ничего"))!!.click()
        SystemClock.sleep(800)
        screenshot("quiz_answer")
        findScrolling(By.text("Дальше"), 6000)!!.click()
        findScrolling(By.text("Матрица"), 6000)!!.click()
        findScrolling(By.text("Итог"), 6000)!!.click()
        assertNotNull("нет итога теста", findScrolling(By.text("1 из 2"), 6000))
        val before = runBlocking { ctx.app.db.cards().observeCount().first() }
        findScrolling(By.text("Ошибки в карточки (1)"), 6000)!!.click()
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
        assertNotNull("нет обратного отсчёта на главном", device.wait(Until.findObject(By.textStartsWith("Математический анализ: экзамен через")), 8000))
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
            assertNotNull("нет раздела расходов", findScrolling(By.text("Без лимита"), 10000))
            SystemClock.sleep(800)
            screenshot("settings_usage_dark")
            assertNotNull("нет резервной копии", findScrolling(By.text("Сохранить копию"), 10000))
            assertNotNull("нет обновлений", findScrolling(By.text("Проверить обновления"), 10000))
            SystemClock.sleep(800)
            screenshot("settings_backup_dark")
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
