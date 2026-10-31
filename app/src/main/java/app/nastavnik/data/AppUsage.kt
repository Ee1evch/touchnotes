package app.nastavnik.data

import app.nastavnik.ai.AiErrors
import app.nastavnik.ai.DayUsage
import app.nastavnik.ai.Pricing
import app.nastavnik.ai.Usage
import app.nastavnik.ai.UsageLog
import app.nastavnik.ai.UsageMeter
import app.nastavnik.study.Streaks

/** Расходы на нейросеть: журнал по дням в настройках и дневной лимит. Ключ сюда не попадает. */
class AppUsage(private val settings: SettingsRepo) : UsageMeter {
    private val lock = Any()

    fun log(): List<DayUsage> = UsageLog.decode(settings.usageJson())

    fun today(now: Long = System.currentTimeMillis()): DayUsage = UsageLog.today(log(), Streaks.day(now))

    fun limitUsd(): Double = settings.dailyLimitUsd().toDouble()

    override fun check() {
        val limit = limitUsd()
        if (limit > 0 && today().usd >= limit) throw AiErrors.budget(UsageLog.money(limit))
    }

    override fun record(model: String, usage: Usage) {
        synchronized(lock) {
            val day = Streaks.day(System.currentTimeMillis())
            settings.setUsageJson(UsageLog.encode(UsageLog.add(log(), day, usage, Pricing.cost(model, usage))))
        }
    }
}
