package app.nastavnik.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.ai.AiException
import app.nastavnik.ai.AiProvider
import app.nastavnik.ai.KeyCheck
import app.nastavnik.ai.ModelPreset
import app.nastavnik.ai.OpenAiCompatPresets
import app.nastavnik.app
import kotlinx.coroutines.launch

private fun AiProvider.hint(): String = when (this) {
    AiProvider.ANTHROPIC -> "Claude Sonnet, Opus, Haiku. Ключ — на console.anthropic.com"
    AiProvider.GEMINI -> "Gemini Flash и Pro. Ключ — бесплатно в Google AI Studio, есть бесплатный лимит"
    AiProvider.OPENAI_COMPAT -> "OpenAI, OpenRouter (сотни моделей), агрегаторы, свои серверы"
}

/** Выбор нейросети, адреса (для OpenAI-совместимых) и ключа. */
@Composable
fun ProviderEditor(onChanged: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.settings
    val s by remember { repo.flow() }.collectAsStateWithLifecycle(initialValue = repo.load())

    AiProvider.entries.forEach { p ->
        RadioRow(s.provider == p, p.title, p.hint()) {
            repo.setProvider(p)
            onChanged()
        }
    }

    if (s.provider == AiProvider.OPENAI_COMPAT) {
        var base by remember { mutableStateOf(s.openAiBaseUrl) }
        Text("Адрес API", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenAiCompatPresets.all.forEach { (label, url) ->
                OutlinedButton(onClick = {
                    base = url
                    repo.setOpenAiBaseUrl(url)
                    onChanged()
                }) { Text(label) }
            }
        }
        OutlinedTextField(
            value = base,
            onValueChange = {
                base = it
                repo.setOpenAiBaseUrl(it)
            },
            label = { Text("Адрес, обычно заканчивается на /v1") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Подойдёт любой сервис с OpenAI-совместимым API (/chat/completions): вставь его адрес и ключ. " +
                "Модель должна понимать картинки.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }

    if (s.provider == AiProvider.GEMINI) {
        Text(
            "Подписка Google AI Pro сама по себе ключ API не даёт. Ключ создаётся бесплатно в Google AI Studio " +
                "(«Get API key»), запросы в пределах бесплатного лимита ничего не стоят.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }

    ApiKeyEditor(s.provider, onSaved = onChanged)

    val keyUrl = when {
        s.provider == AiProvider.OPENAI_COMPAT && "openrouter" in s.openAiBaseUrl -> "https://openrouter.ai/keys"
        else -> s.provider.keyUrl
    }
    TextButton(onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl))) }
    }) { Text("Где взять ключ: ${Uri.parse(keyUrl).host}") }

    if (!repo.secureStorageOk) {
        Text(
            "Зашифрованное хранилище недоступно: ключ сохранится только до перезапуска приложения.",
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Поле ключа выбранного провайдера со скрытием и кнопкой «Проверить ключ». */
@Composable
fun ApiKeyEditor(provider: AiProvider, onSaved: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.settings
    val scope = rememberCoroutineScope()
    var key by remember(provider) { mutableStateOf(repo.apiKey(provider)) }
    var visible by remember { mutableStateOf(false) }
    var status by remember(provider) { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = key,
        onValueChange = { key = it.trim() },
        label = { Text("API-ключ ${provider.shortTitle}: ${provider.keyPlaceholder}") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "Скрыть" else "Показать") }
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = {
            repo.setApiKey(key, provider)
            status = if (key.isBlank()) "Ключ удалён" else "Ключ сохранён"
            onSaved()
        }) { Text("Сохранить") }
        OutlinedButton(enabled = !checking && key.isNotBlank(), onClick = {
            repo.setApiKey(key, provider)
            onSaved()
            checking = true
            status = "Проверяю…"
            scope.launch {
                val s = repo.load()
                status = when (val r = context.app.ai(s).checkKey(key, s.model)) {
                    KeyCheck.Ok -> "✓ Ключ работает, модель ${s.model} доступна"
                    is KeyCheck.ModelMissing -> "Ключ работает, но модели ${r.modelId} нет — выбери другую ниже"
                    is KeyCheck.Failed -> r.message
                }
                checking = false
            }
        }) { Text("Проверить ключ") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

/**
 * Выбор модели: готовые варианты, своё название и список, загруженный прямо из API провайдера.
 */
@Composable
fun ModelPicker(provider: AiProvider, presets: List<ModelPreset>, current: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    presets.forEach { m ->
        RadioRow(current == m.id, "${m.label} — ${m.hint}", m.id) { onPick(m.id) }
    }
    val isCustom = presets.none { it.id == current }
    var custom by remember(provider) { mutableStateOf(if (isCustom) current else "") }
    RadioRow(isCustom, "Своя модель", if (isCustom) current else null) { if (custom.isNotBlank()) onPick(custom) }
    OutlinedTextField(
        value = custom,
        onValueChange = {
            custom = it.trim()
            if (custom.isNotBlank()) onPick(custom)
        },
        label = { Text("Название модели (id)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    var loading by remember { mutableStateOf(false) }
    var error by remember(provider) { mutableStateOf<String?>(null) }
    var models by remember(provider) { mutableStateOf<List<String>?>(null) }
    OutlinedButton(enabled = !loading, onClick = {
        loading = true
        error = null
        scope.launch {
            try {
                val s = context.app.settings.load()
                val list = context.app.ai(s).listModels(context.app.settings.apiKey(s.provider))
                if (list.isEmpty()) error = "Сервис вернул пустой список моделей." else models = list
            } catch (e: AiException) {
                error = e.userMessage
            } finally {
                loading = false
            }
        }
    }) { Text(if (loading) "Загружаю…" else "Загрузить список моделей") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    models?.let { list ->
        ModelListDialog(list, onDismiss = { models = null }) { picked ->
            custom = if (presets.any { it.id == picked }) "" else picked
            onPick(picked)
            models = null
        }
    }
}

@Composable
private fun ModelListDialog(models: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val shown = models.filter { filter.isBlank() || it.contains(filter.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Модели (${models.size})") },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Поиск, например: gemini, gpt, claude, vision") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown) { id ->
                        Text(
                            id,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(id) }
                                .padding(vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}
