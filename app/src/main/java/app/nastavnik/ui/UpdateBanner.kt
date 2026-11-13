package app.nastavnik.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.data.TrustResult
import kotlinx.coroutines.launch

/**
 * Баннер обновления приложения прямо на главном экране.
 * Позволяет установить новую сборку в 1 клик без перехода на GitHub.
 */
@Composable
fun InAppUpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val update by app.availableUpdate.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val u = update
    AnimatedVisibility(
        visible = u != null && !dismissed,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        if (u == null) return@AnimatedVisibility

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brand.Glass)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Доступно обновление: ${u.title}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Brand.Ink,
                        )
                        val sizeText = if (u.sizeBytes > 0) " · ${u.sizeBytes / 1_000_000} МБ" else ""
                        Text(
                            "Обновление ставится поверх$sizeText, данные сохраняются.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.InkSoft,
                        )
                    }
                    RoundIconButton(R.drawable.ic_close, "Закрыть") { dismissed = true }
                }

                if (progress >= 0f) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Brand.Line)) {
                        Box(Modifier.fillMaxWidth(progress).height(6.dp).clip(CircleShape).background(Brand.Accent))
                    }
                    Text("Скачиваю… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        InkButton("Обновить прямо сейчас", icon = R.drawable.ic_refresh) {
                            val updater = app.updater
                            if (!updater.canInstall()) {
                                errorText = "Разрешите установку обновлений в настройках Android"
                                updater.openInstallPermission()
                                return@InkButton
                            }
                            progress = 0f
                            errorText = null
                            scope.launch {
                                runCatching {
                                    val apk = updater.download(u) { progress = it }
                                    val trust = updater.verify(apk, u.sha256)
                                    when (trust) {
                                        is TrustResult.Valid -> updater.install(apk)
                                        is TrustResult.Rejected -> errorText = trust.reason
                                    }
                                }.onFailure {
                                    errorText = "Ошибка загрузки: ${it.message}"
                                }
                                progress = -1f
                            }
                        }
                    }
                }

                errorText?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodySmall, color = Brand.Error)
                }
            }
        }
    }
}
