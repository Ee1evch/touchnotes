package app.nastavnik.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/** Голосовой ввод через системный SpeechRecognizer (русский язык). */
class SpeechInput(private val context: Context, private val callback: Callback) {

    interface Callback {
        fun onListening(active: Boolean)
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(message: String, needsPermission: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    var isListening = false
        private set

    fun toggle() {
        if (isListening) stop() else start()
    }

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callback.onError("Нет доступа к микрофону. Разреши его в приложении «Наставник».", true)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.onError("На планшете нет сервиса распознавания речи. Установи или включи «Google» / «Распознавание речи».", false)
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            r.startListening(intent)
            isListening = true
            callback.onListening(true)
        } catch (e: Exception) {
            callback.onError("Не удалось включить микрофон: ${e.message}", false)
        }
    }

    fun stop() {
        recognizer?.stopListening()
        isListening = false
        callback.onListening(false)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(callback::onPartial)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            callback.onListening(false)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) callback.onResult(text) else callback.onError("Не расслышал. Попробуй ещё раз.", false)
        }

        override fun onError(error: Int) {
            isListening = false
            callback.onListening(false)
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал. Нажми на микрофон и говори чуть громче."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Для распознавания речи нужен интернет."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону. Разреши его в приложении «Наставник»."
                SpeechRecognizer.ERROR_AUDIO -> "Микрофон занят другим приложением."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание уже идёт — подожди секунду."
                else -> "Ошибка распознавания речи (код $error)."
            }
            callback.onError(msg, error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        }
    }
}
