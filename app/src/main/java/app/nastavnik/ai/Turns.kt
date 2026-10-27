package app.nastavnik.ai

import java.util.Base64

enum class Role(val wire: String) { USER("user"), ASSISTANT("assistant") }

/** Одна реплика разговора в том виде, в каком она хранится в истории (только текст). */
data class ChatTurn(val role: Role, val text: String, val hadImage: Boolean = false)

/** Картинка для последнего сообщения. */
class ImagePart(val bytes: ByteArray, val mediaType: String = "image/jpeg") {
    fun base64(): String = Base64.getEncoder().encodeToString(bytes)
}

/** Подготовка истории для любого провайдера. */
object Turns {
    /**
     * - картинки уходят только в последнем сообщении пользователя, в прошлых — текстовая пометка;
     * - подряд идущие реплики одной роли склеиваются (API требуют чередования);
     * - пустые ответы ассистента (например, после ошибки) выбрасываются;
     * - история всегда начинается с пользователя и заканчивается им.
     */
    fun normalize(input: List<ChatTurn>): List<ChatTurn> {
        val out = ArrayList<ChatTurn>()
        for ((index, raw) in input.withIndex()) {
            val isLast = index == input.lastIndex
            var text = raw.text.trim()
            if (raw.role == Role.ASSISTANT && text.isEmpty()) continue
            if (raw.role == Role.USER && raw.hadImage && !isLast) {
                text = if (text.isEmpty()) Prompts.PAST_IMAGE_NOTE else "${Prompts.PAST_IMAGE_NOTE}\n$text"
            }
            if (text.isEmpty()) text = "…"
            val prev = out.lastOrNull()
            if (prev != null && prev.role == raw.role) {
                out[out.lastIndex] = prev.copy(text = prev.text + "\n\n" + text, hadImage = prev.hadImage || raw.hadImage)
            } else {
                out += raw.copy(text = text)
            }
        }
        while (out.isNotEmpty() && out.first().role == Role.ASSISTANT) out.removeAt(0)
        require(out.isNotEmpty() && out.last().role == Role.USER) { "Последним должно быть сообщение пользователя" }
        return out
    }
}
