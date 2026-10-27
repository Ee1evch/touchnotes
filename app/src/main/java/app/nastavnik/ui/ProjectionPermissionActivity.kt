package app.nastavnik.ui

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nastavnik.app
import app.nastavnik.overlay.BubbleService

/**
 * Один понятный экран перед системным диалогом записи экрана.
 * Объяснение показывается при первом запросе, дальше сразу открывается системный диалог
 * (на Android 14+ его приходится подтверждать в каждой новой сессии).
 */
class ProjectionPermissionActivity : ComponentActivity() {
    private var delivered = false

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            deliver(
                Intent(this, BubbleService::class.java)
                    .setAction(BubbleService.ACTION_PROJECTION_RESULT)
                    .putExtra(BubbleService.EXTRA_RESULT_CODE, r.resultCode)
                    .putExtra(BubbleService.EXTRA_RESULT_DATA, data),
            )
        } else {
            deny()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (app.settings.load().projectionExplained) {
            launchSystemDialog()
        } else {
            setContent {
                NastavnikTheme {
                    Explanation(
                        onAllow = {
                            app.settings.setProjectionExplained(true)
                            launchSystemDialog()
                        },
                        onCancel = {
                            deny()
                            finish()
                        },
                    )
                }
            }
        }
    }

    private fun launchSystemDialog() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Просим сразу весь экран, без выбора «одно приложение».
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            launcher.launch(intent)
        } catch (_: Exception) {
            deny()
            finish()
        }
    }

    private fun deliver(intent: Intent) {
        delivered = true
        runCatching { startService(intent) }
    }

    private fun deny() {
        if (!delivered) deliver(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_PROJECTION_DENIED))
    }

    override fun onDestroy() {
        // Закрыли «назад» — сервис не должен ждать вечно.
        if (isFinishing) deny()
        super.onDestroy()
    }
}

@Composable
private fun Explanation(onAllow: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .softCard(RoundedCornerShape(28.dp), 24.dp, MaterialTheme.colorScheme.surface)
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brand.gradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(app.nastavnik.R.drawable.ic_search), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Text("Разреши снимок экрана", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Наставник смотрит на экран только когда ты нажимаешь кнопку — чтобы увидеть твои записи в Touchnotes. " +
                    "Снимок уходит только в выбранную нейросеть и больше никуда.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "В следующем окне выбери «Весь экран» и нажми «Начать». " +
                    "На Android 14 и новее система спрашивает это заново при каждом запуске наставника.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                SoftButton("Не сейчас", onClick = onCancel)
                InkButton("Разрешить", onClick = onAllow)
            }
        }
    }
}
