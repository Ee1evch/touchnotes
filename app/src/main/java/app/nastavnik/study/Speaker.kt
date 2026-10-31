package app.nastavnik.study

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Читает подсказки вслух (системный синтез речи, русский язык). Формулы — словами, без разметки. */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    /** Прочитать текст подсказки (Markdown + LaTeX). Новое чтение прерывает предыдущее. */
    fun speak(markdown: String) {
        val text = SpeakText.from(markdown)
        if (text.isBlank()) return
        val t = tts
        if (t == null) {
            pending = text
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale("ru", "RU")
                    pending?.let { say(it) }
                }
                pending = null
            }
            return
        }
        if (ready) say(text) else pending = text
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hint")
    }

    fun stop() {
        tts?.stop()
    }
}
