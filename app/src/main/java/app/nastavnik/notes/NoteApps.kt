package app.nastavnik.notes

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.nastavnik.app
import app.nastavnik.data.SettingsRepo
import java.io.File

/**
 * Приложение, в котором студент пишет конспекты, — любое: Touchnotes, Samsung Notes, OneNote, Squid, Nebo,
 * Xodo, Flexcil и другие. Его можно выбрать в настройках, а если не выбрано — находим сами среди известных.
 * Чистовик отдаём туда через обычный «Поделиться» (API у таких приложений нет).
 */
object NoteApps {

    data class Target(val packageName: String, val activityName: String, val label: String, val action: String) {
        fun encode(): String = "$packageName/$activityName|$action"
    }

    /** Приложение для записей: пакет и подпись. */
    data class App(val packageName: String, val label: String)

    /** Известные приложения для рукописных конспектов — по части имени пакета, в порядке, в котором их предлагать. */
    private val KNOWN = listOf(
        "touchnotes", "com.samsung.android.app.notes", "onenote", "projectpapyrus", "com.myscript.nebo", "xodo",
        "flexcil", "noteshelf", "goodnotes", "notewise", "kilonotes", "com.google.android.keep",
    )

    private fun knownRank(pkg: String): Int = KNOWN.indexOfFirst { pkg.contains(it, ignoreCase = true) }

    /** Все установленные приложения, которые можно открыть, — для выбора «другого» приложения. */
    fun launchable(context: Context): List<App> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return query(pm, main).mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            if (ai.packageName == context.packageName) null else App(ai.packageName, ri.loadLabel(pm).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    /** Установленные известные приложения для записей. */
    fun installedKnown(context: Context): List<App> =
        launchable(context).filter { knownRank(it.packageName) >= 0 }.sortedBy { knownRank(it.packageName) }

    /** Выбранное в настройках приложение (если оно ещё установлено) или первое найденное известное. */
    fun chosen(context: Context): App? {
        val pick = context.app.settings.notesApp()
        val all = launchable(context)
        return all.firstOrNull { it.packageName == pick && pick.isNotEmpty() }
            ?: all.filter { knownRank(it.packageName) >= 0 }.minByOrNull { knownRank(it.packageName) }
    }

    /** Открыть приложение для записей. */
    fun open(context: Context, app: App): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    sealed interface Outcome {
        data class Direct(val label: String) : Outcome
        data object Chooser : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun decode(s: String): Target? {
        val bar = s.lastIndexOf('|')
        val slash = s.indexOf('/')
        if (bar < 0 || slash < 0 || slash > bar) return null
        return Target(s.substring(0, slash), s.substring(slash + 1, bar), "", s.substring(bar + 1))
    }

    private fun authority(context: Context) = "${context.packageName}.files"

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** Все приложения, которые принимают PDF (через «Поделиться» или «Открыть»). */
    fun pdfTargets(context: Context): List<Target> {
        val pm = context.packageManager
        val probe = Uri.parse("content://${authority(context)}/notes/probe.pdf")
        val send = Intent(Intent.ACTION_SEND).setType(MIME_PDF)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(probe, MIME_PDF)
        val out = LinkedHashMap<String, Target>()
        query(pm, send).forEach { ri -> ri.toTarget(pm, Intent.ACTION_SEND)?.let { out.putIfAbsent(it.packageName + "/" + it.activityName, it) } }
        query(pm, view).forEach { ri -> ri.toTarget(pm, Intent.ACTION_VIEW)?.let { out.putIfAbsent(it.packageName + "/" + it.activityName, it) } }
        val notes = chosen(context)?.packageName
        return out.values
            .filter { it.packageName != context.packageName }
            .sortedWith(compareByDescending<Target> { it.packageName == notes }.thenBy { it.label.lowercase() })
    }

    /** Как отдать PDF приложению для записей: «Поделиться» лучше, чем «Открыть» — так файл импортируется в заметки. */
    fun notesTarget(context: Context): Target? {
        val pkg = chosen(context)?.packageName ?: return null
        val all = pdfTargets(context).filter { it.packageName == pkg }
        return all.firstOrNull { it.action == Intent.ACTION_SEND } ?: all.firstOrNull()
    }

    /** Отправить PDF согласно настройке [preference] (auto / chooser / конкретное приложение). */
    fun sendPdf(context: Context, pdf: File, title: String, preference: String): Outcome {
        val uri = try {
            uriFor(context, pdf)
        } catch (e: Exception) {
            return Outcome.Failed("Не удалось подготовить файл: ${e.message}")
        }
        val target = when (preference) {
            SettingsRepo.TARGET_CHOOSER -> null
            SettingsRepo.TARGET_AUTO -> notesTarget(context)
            else -> decode(preference)?.takeIf { isInstalled(context, it) } ?: notesTarget(context)
        }
        return try {
            if (target != null) {
                val intent = buildIntent(target.action, uri, MIME_PDF, title)
                    .setComponent(ComponentName(target.packageName, target.activityName))
                context.grantUriPermission(target.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
                Outcome.Direct(labelOf(context, target))
            } else {
                openChooser(context, buildIntent(Intent.ACTION_SEND, uri, MIME_PDF, title))
                Outcome.Chooser
            }
        } catch (e: Exception) {
            // Например, приложение удалили или оно не приняло файл — показываем системный выбор.
            runCatching { openChooser(context, buildIntent(Intent.ACTION_SEND, uri, MIME_PDF, title)) }
                .fold({ Outcome.Chooser }, { Outcome.Failed("Не удалось отправить файл: ${e.message}") })
        }
    }

    /** Отправить PNG-страницы (их удобно вставлять картинками в заметку). */
    fun sendImages(context: Context, pngs: List<File>, title: String): Outcome {
        if (pngs.isEmpty()) return Outcome.Failed("Нет картинок")
        val uris = try {
            pngs.map { uriFor(context, it) }
        } catch (e: Exception) {
            return Outcome.Failed("Не удалось подготовить картинки: ${e.message}")
        }
        val intent = if (uris.size == 1) {
            buildIntent(Intent.ACTION_SEND, uris[0], MIME_PNG, title)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = MIME_PNG
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData = ClipData.newRawUri(title, uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val pm = context.packageManager
        val notes = chosen(context)?.packageName
        val touch = query(pm, Intent(intent.action).setType(MIME_PNG))
            .mapNotNull { it.toTarget(pm, intent.action!!) }
            .firstOrNull { it.packageName == notes }
        return try {
            if (touch != null) {
                intent.component = ComponentName(touch.packageName, touch.activityName)
                uris.forEach { context.grantUriPermission(touch.packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                context.startActivity(intent)
                Outcome.Direct(touch.label)
            } else {
                openChooser(context, intent)
                Outcome.Chooser
            }
        } catch (e: Exception) {
            Outcome.Failed("Не удалось отправить картинки: ${e.message}")
        }
    }

    /** Открыть PDF для просмотра (из истории). */
    fun viewPdf(context: Context, pdf: File): Outcome = try {
        val uri = uriFor(context, pdf)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME_PDF)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Открыть конспект").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Outcome.Chooser
    } catch (e: Exception) {
        Outcome.Failed("Нет приложения для просмотра PDF")
    }

    private fun buildIntent(action: String, uri: Uri, mime: String, title: String): Intent =
        Intent(action).apply {
            if (action == Intent.ACTION_VIEW) {
                setDataAndType(uri, mime)
            } else {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TITLE, title)
            }
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun openChooser(context: Context, intent: Intent) {
        val chooser = Intent.createChooser(intent, "Отправить конспект")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }

    private fun isInstalled(context: Context, t: Target): Boolean = try {
        context.packageManager.getActivityInfo(ComponentName(t.packageName, t.activityName), 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun labelOf(context: Context, t: Target): String = t.label.ifBlank {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(t.packageName, 0)).toString()
        }.getOrDefault(t.packageName)
    }

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }

    private fun ResolveInfo.toTarget(pm: PackageManager, action: String): Target? {
        val ai = activityInfo ?: return null
        return Target(ai.packageName, ai.name, loadLabel(pm).toString(), action)
    }

    const val MIME_PDF = "application/pdf"
    const val MIME_PNG = "image/png"
}
