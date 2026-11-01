package app.nastavnik.ui

import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nastavnik.app
import app.nastavnik.study.SleepPlan
import app.nastavnik.study.WakeAlarm
import app.nastavnik.study.WakePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Экран будильника поверх блокировки. Выключить — только решив пример (если включено в настройках):
 * спящий нажмёт «стоп» не просыпаясь, а сложить два числа — уже нет.
 * В режиме проверки («Ты встал?») — одна большая кнопка.
 */
class WakeActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CHECK = "check"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        val check = intent.getBooleanExtra(EXTRA_CHECK, false)
        val test = intent.getBooleanExtra(WakeAlarm.EXTRA_TEST, false)
        // Тема — как во всём приложении. Цвета темы общие: со светлой по умолчанию будильник перекрашивал
        // и приложение. До первой разблокировки настройки недоступны — тогда тема по умолчанию.
        val appearance = if (WakePrefs.unlocked(this)) appearanceOf(app.settings.load()) else Appearance()
        setContent {
            NastavnikTheme(appearance) {
                if (check) CheckScreen { app.appScope.launch { WakeAlarm.onAwake(this@WakeActivity) }; finish() }
                else RingScreen(test, onDone = { finish() })
            }
        }
    }
}

@Composable
private fun Clock() {
    val now by produceState(LocalDateTime.now()) { while (true) { delay(1000); value = LocalDateTime.now() } }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val s by pulse.animateFloat(1f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Text(
        WakeAlarm.hm(now),
        fontSize = 84.sp, fontWeight = FontWeight.Light, color = Brand.Ink,
        modifier = Modifier.scale(s),
    )
}

@Composable
private fun RingScreen(test: Boolean, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val w = remember { WakePrefs.of(context) }
    val scope = rememberCoroutineScope()
    // Только то, что запомнено вместе с будильником, — работает и до разблокировки после перезагрузки.
    val lessonAt = remember { w.lessonAt.takeIf { it > 0 }?.let(WakeAlarm::time) }
    val canSnooze = remember { !test && WakeAlarm.canSnooze(context) }
    val math = w.math
    val seed = remember { System.currentTimeMillis() / 60_000L }
    val (task, answer) = remember(seed) { SleepPlan.task(seed) }
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val solved = !math || input.trim().toIntOrNull() == answer
    // Кнопкой «Назад» будильник не выключить.
    BackHandler { }
    LaunchedEffect(solved) {
        if (solved && math) {
            delay(250)
            WakeAlarm.dismiss(context, test)
            onDone()
        }
    }
    Column(
        // Клавиатура поднимает поле ответа, а не закрывает его: отступ под клавиатуру и прокрутка.
        Modifier.fillMaxSize().paperBackdrop().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(if (test) "Проверка будильника" else "Доброе утро", style = MaterialTheme.typography.titleLarge, color = Brand.InkSoft)
        Clock()
        lessonAt?.let { at ->
            Text(
                "Первая пара в ${WakeAlarm.hm(at)}" + (w.lessonLabel.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                style = MaterialTheme.typography.titleMedium, color = Brand.Ink, textAlign = TextAlign.Center,
            )
            val bed = w.bedAt.takeIf { it > 0 }?.let(WakeAlarm::time)
            if (bed != null) {
                val cycles = SleepPlan.cycles(bed, LocalDateTime.now())
                Text(
                    "Ты проспал $cycles ${if (cycles in 2..4) "цикла" else if (cycles == 1) "цикл" else "циклов"} — просыпаешься в конце цикла",
                    style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft, textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        if (math) {
            Column(
                Modifier.fillMaxWidth().glass(androidx.compose.foundation.shape.RoundedCornerShape(28.dp), 10.dp, strong = true).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Реши, чтобы выключить", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
                Text("$task = ?", fontSize = 40.sp, fontWeight = FontWeight.Medium, color = Brand.Ink)
                AppTextField(
                    value = input,
                    onValueChange = { v -> input = v.filter { it.isDigit() || it == '-' }.take(5); wrong = false },
                    label = "Ответ",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                AnimatedVisibility(wrong, enter = fadeIn() + slideInVertically()) {
                    Text("Не то — посчитай ещё раз", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                LaunchedEffect(input) {
                    if (input.length >= answer.toString().length && input.toIntOrNull() != answer) wrong = true
                }
            }
        } else {
            InkButton("Встаю", Modifier.fillMaxWidth()) {
                scope.launch { WakeAlarm.dismiss(context, test); onDone() }
            }
        }
        if (canSnooze) {
            SoftButton("Ещё ${WakeAlarm.SNOOZE_MIN} минут · осталось ${WakeAlarm.MAX_SNOOZES - w.snoozes}", Modifier.fillMaxWidth()) {
                scope.launch { WakeAlarm.snooze(context); onDone() }
            }
        } else if (!test) {
            Text("Отложить уже нельзя — иначе не успеешь собраться", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, textAlign = TextAlign.Center)
        } else {
            SoftButton("Выключить проверку", Modifier.fillMaxWidth()) {
                scope.launch { WakeAlarm.dismiss(context, true); onDone() }
            }
        }
    }
}

@Composable
private fun CheckScreen(onAwake: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { context.getSystemService(NotificationManager::class.java)?.cancel(WakeAlarm.NOTIFY_CHECK) }
    Box(Modifier.fillMaxSize().paperBackdrop().background(Brand.Paper.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Clock()
            Text("Ты встал?", style = MaterialTheme.typography.headlineMedium, color = Brand.Ink)
            Text(
                "Если не нажмёшь — через ${WakeAlarm.CHECK_GRACE_MIN} минуты будильник зазвонит снова.",
                style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft, textAlign = TextAlign.Center,
            )
            InkButton("Да, встал", Modifier.fillMaxWidth(), onClick = onAwake)
        }
    }
}
