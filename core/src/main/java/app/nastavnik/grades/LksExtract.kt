package app.nastavnik.grades

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ЛКС Сириуса (lks.siriusuniversity.ru) пускает только с российских адресов, поэтому его страницу открывает
 * сам телефон во встроенном окне, а сюда приходят таблицы со страницы успеваемости — как их видит человек.
 * Здесь из таблиц собираются курсы и оценки: столбец с названием дисциплины и столбец с оценкой ищутся
 * по заголовкам («Дисциплина», «Оценка», «Итог», «Балл»…), остальные столбцы — подробности.
 */
object LksExtract {
    /** Таблица со страницы: заголовки и строки (текст ячеек). */
    @Serializable
    data class Table(val headers: List<String> = emptyList(), val rows: List<List<String>> = emptyList(), val caption: String = "")

    @Serializable
    data class Page(val url: String = "", val title: String = "", val tables: List<Table> = emptyList(), val student: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    private val subjectWords = listOf("дисциплин", "предмет", "курс", "наименование", "модул", "практик")
    private val gradeWords = listOf("оценк", "итог", "балл", "результат", "отметк", "рейтинг")

    fun parse(pageJson: String, now: Long = System.currentTimeMillis()): GradesSnapshot? =
        runCatching { fromPage(json.decodeFromString(Page.serializer(), pageJson), now) }.getOrNull()

    fun fromPage(page: Page, now: Long = System.currentTimeMillis()): GradesSnapshot? {
        val courses = ArrayList<CourseGrades>()
        for ((ti, t) in page.tables.withIndex()) {
            val rows = t.rows.map { r -> r.map { clean(it) } }.filter { r -> r.any { it.isNotBlank() } }
            if (rows.isEmpty()) continue
            var headers = t.headers.map { clean(it).lowercase() }
            var body = rows
            // Заголовков в <th> нет — первая строка может быть заголовком.
            if (headers.isEmpty() && rows.size > 1 && rows[0].any { h -> (subjectWords + gradeWords).any { h.lowercase().contains(it) } }) {
                headers = rows[0].map { it.lowercase() }
                body = rows.drop(1)
            }
            val subjectCol = headers.indexOfFirst { h -> subjectWords.any { h.contains(it) } }
            val gradeCol = headers.indexOfLast { h -> gradeWords.any { h.contains(it) } && !h.contains("дата") }
            val (sc, gc) = when {
                subjectCol >= 0 && gradeCol >= 0 && subjectCol != gradeCol -> subjectCol to gradeCol
                headers.isEmpty() && body.size >= 2 && body.all { it.size >= 2 } -> 0 to (body[0].size - 1)
                else -> continue
            }
            for ((ri, r) in body.withIndex()) {
                val name = r.getOrNull(sc)?.takeIf { it.isNotBlank() } ?: continue
                val grade = r.getOrNull(gc).orEmpty().ifBlank { "-" }
                val details = r.mapIndexedNotNull { i, v ->
                    if (i == sc || i == gc || v.isBlank()) null
                    else GradeItem(id = (ti * 1000 + ri * 10 + i).toLong(), name = headers.getOrNull(i)?.replaceFirstChar { it.uppercase() } ?: "Столбец ${i + 1}", grade = v, type = "info")
                }
                val section = t.caption.takeIf { it.isNotBlank() }
                courses += CourseGrades(
                    id = (name + (section ?: "")).hashCode().toLong(),
                    name = if (section != null && page.tables.size > 1) "$name · $section" else name,
                    total = grade.takeIf { it != "-" },
                    totalRaw = numeric(grade),
                    items = details,
                )
            }
        }
        if (courses.isEmpty()) return null
        return GradesSnapshot(page.student, courses.distinctBy { it.name to it.total }, now)
    }

    /** «5», «4,5», «отлично», «зачтено» → число для средних (5-балльная шкала), иначе null. */
    fun numeric(g: String): Double? {
        val t = g.lowercase().trim()
        t.replace(',', '.').toDoubleOrNull()?.let { return it }
        return when {
            t.startsWith("отл") -> 5.0
            t.startsWith("хор") -> 4.0
            t.startsWith("удовл") -> 3.0
            t.startsWith("неуд") -> 2.0
            else -> null
        }
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /**
     * Скрипт для страницы: собирает все таблицы (заголовки и ячейки) и имя студента, если найдётся,
     * и отдаёт JSON в Android через NastavnikLks.page(...).
     */
    const val EXTRACT_JS = """
(function(){
  function txt(e){return (e.innerText||e.textContent||'').replace(/\s+/g,' ').trim();}
  var tables=[];
  document.querySelectorAll('table').forEach(function(t){
    var headers=[];
    var th=t.querySelectorAll('thead th, thead td');
    if(th.length){th.forEach(function(h){headers.push(txt(h));});}
    var rows=[];
    t.querySelectorAll('tbody tr, tr').forEach(function(tr){
      if(tr.closest('thead'))return;
      var cells=[];tr.querySelectorAll('td,th').forEach(function(c){cells.push(txt(c));});
      if(cells.length)rows.push(cells);
    });
    var cap=t.querySelector('caption');
    tables.push({headers:headers,rows:rows,caption:cap?txt(cap):''});
  });
  // Таблицы из div-ов (role="table"/"row"/"cell"), если их нет в <table>.
  document.querySelectorAll('[role=table]').forEach(function(t){
    var rows=[];t.querySelectorAll('[role=row]').forEach(function(r){var c=[];r.querySelectorAll('[role=cell],[role=gridcell],[role=columnheader]').forEach(function(x){c.push(txt(x));});if(c.length)rows.push(c);});
    tables.push({headers:[],rows:rows,caption:''});
  });
  var who=document.querySelector('[class*=user-name],[class*=userName],[class*=profile-name],[class*=fio]');
  return JSON.stringify({url:location.href,title:document.title,tables:tables,student:who?txt(who):''});
})()
"""

    /** Перехват ответов сайта (JSON) — для отчёта, по которому можно сделать точный разбор. */
    const val CAPTURE_JS = """
(function(){
  if(window.__nastavnikHook)return;window.__nastavnikHook=true;
  function send(u,t){try{if(t&&t.length<400000&&/^[\s]*[\[{]/.test(t))NastavnikLks.capture(String(u),t);}catch(e){}}
  var f=window.fetch;
  if(f)window.fetch=function(){var a=arguments;return f.apply(this,a).then(function(r){try{r.clone().text().then(function(t){send(r.url||a[0],t);});}catch(e){}return r;});};
  var o=XMLHttpRequest.prototype.open,s=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(m,u){this.__u=u;return o.apply(this,arguments);};
  XMLHttpRequest.prototype.send=function(){var x=this;x.addEventListener('load',function(){try{if(typeof x.responseText==='string')send(x.__u,x.responseText);}catch(e){}});return s.apply(this,arguments);};
})()
"""
}
