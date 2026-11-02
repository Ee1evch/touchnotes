package app.nastavnik.grades

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.nastavnik.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Раз в 3 часа (когда есть сеть) — свежий журнал ЛКС и синхронизация: оценки доходят до планшета и родителя,
 * даже если студент не открывал приложение. Только на устройстве, где вход в ЛКС; уведомление о новых — как обычно.
 */
class GradesJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext.app
        if (!app.settings.lksConfigured) return false
        job = CoroutineScope(Dispatchers.IO).launch {
            runCatching { app.grades.refreshLks(force = true) }
            runCatching { if (app.sync.configured) app.sync.now() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        job?.cancel()
        return true
    }

    companion object {
        private const val ID = 7740

        /** Поставить, если ещё не стоит (повторный вызов ничего не меняет). */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            runCatching {
                js.schedule(
                    JobInfo.Builder(ID, ComponentName(context, GradesJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(3 * 60 * 60 * 1000L, 30 * 60 * 1000L)
                        .setPersisted(true)
                        .build(),
                )
            }
        }
    }
}
