package app.nastavnik.theme

/**
 * Спецификация токенов темы оформления (чистый Kotlin без привязки к Compose).
 * Включает цвета фона (surface), карточек (card), текста (ink/inkSoft),
 * акцентов (accent/onAccent), линий и параметры стекла.
 */
data class ThemeSpec(
    val id: String,
    val name: String,
    val dark: Boolean,
    /** Основной фон экрана (surface) */
    val surface: Long,
    /** Фон карточек / стекла (card) */
    val card: Long,
    /** Основной цвет текста (ink) */
    val ink: Long,
    /** Второстепенный цвет текста (inkSoft) */
    val inkSoft: Long,
    /** Акцентный цвет кнопок и переключателей */
    val accent: Long,
    /** Текст на акцентном фоне */
    val onAccent: Long,
    /** Разделительная линия */
    val line: Long,
    /** Прозрачность стекла (0..1) */
    val glassAlpha: Float = 0.85f,
    /** Размытие стекла в dp */
    val glassBlurDp: Float = 16f,
    /** Декоративные пятна фона */
    val blobA: Long = surface,
    val blobB: Long = surface,
    val blobC: Long = surface,
)

/**
 * Расчёт контраста и относительной яркости по стандарту WCAG 2.1.
 */
object ContrastRatio {
    fun relativeLuminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0

        fun channel(c: Double): Double =
            if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun calculate(color1: Long, color2: Long): Double {
        val l1 = relativeLuminance(color1)
        val l2 = relativeLuminance(color2)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Проверка WCAG AA для обычного текста (>= 4.5:1) */
    fun isWcagAaNormalText(foreground: Long, background: Long): Boolean =
        calculate(foreground, background) >= 4.5

    /** Проверка WCAG AA для крупного текста или графических элементов (>= 3.0:1) */
    fun isWcagAaLargeText(foreground: Long, background: Long): Boolean =
        calculate(foreground, background) >= 3.0
}

/**
 * Курируемый набор тем оформления:
 * 1. «Как сейчас» (светлая)
 * 2. «Графит» (тёмная)
 * 3. «Бумага и перо» (тёплая светлая)
 * 4. «Север» (холодная светлая/нордическая)
 * 5. «Шалфей» (приглушённая зелёная)
 * 6. «Киберпанк» (высококонтрастная тёмная с неоном)
 */
object ThemeTokens {
    // 1. «Как сейчас» (светлая) — точное сохранение текущего вида приложения
    val AS_IS = ThemeSpec(
        id = "paper",
        name = "Как сейчас",
        dark = false,
        surface = 0xFFF4F5F8L,
        card = 0xFFFFFFFFL,
        ink = 0xFF16161AL,
        inkSoft = 0xFF595D68L,
        accent = 0xFF2563EBL,
        onAccent = 0xFFFFFFFFL,
        line = 0xFFE2E4EAL,
        glassAlpha = 0.85f,
        glassBlurDp = 16f,
        blobA = 0xFFD5E2F3L,
        blobB = 0xFFF5DECDL,
        blobC = 0xFFD5ECE1L,
    )

    // 2. «Графит» (тёмная)
    val GRAPHITE = ThemeSpec(
        id = "graphite",
        name = "Графит",
        dark = true,
        surface = 0xFF111216L,
        card = 0xFF1B1C21L,
        ink = 0xFFF2F3F5L,
        inkSoft = 0xFFA3A7B1L,
        accent = 0xFF8AB4FFL,
        onAccent = 0xFF0B1B3AL,
        line = 0xFF33353CL,
        glassAlpha = 0.85f,
        glassBlurDp = 16f,
        blobA = 0xFF1C2A3DL,
        blobB = 0xFF3A2A20L,
        blobC = 0xFF183028L,
    )

    // 3. «Бумага и перо» (тёплая светлая)
    val WARM_PAPER = ThemeSpec(
        id = "warm_paper",
        name = "Бумага и перо",
        dark = false,
        surface = 0xFFF9F6F0L,
        card = 0xFFFFFDFAL,
        ink = 0xFF2C241CL,
        inkSoft = 0xFF6E6254L,
        accent = 0xFF8B4513L,
        onAccent = 0xFFFFFFFFL,
        line = 0xFFE8E0D2L,
        glassAlpha = 0.85f,
        glassBlurDp = 16f,
        blobA = 0xFFF0E5D4L,
        blobB = 0xFFF5EADAL,
        blobC = 0xFFE8DFCEL,
    )

    // 4. «Север» (холодная светлая/нордическая)
    val NORDIC = ThemeSpec(
        id = "nordic",
        name = "Север",
        dark = false,
        surface = 0xFFECEFF4L,
        card = 0xFFF8FAFFL,
        ink = 0xFF1E293BL,
        inkSoft = 0xFF475569L,
        accent = 0xFF0369A1L,
        onAccent = 0xFFFFFFFFL,
        line = 0xFFCBD5E1L,
        glassAlpha = 0.85f,
        glassBlurDp = 16f,
        blobA = 0xFFD1E5F7L,
        blobB = 0xFFD8E4ECL,
        blobC = 0xFFCDE0EDL,
    )

    // 5. «Шалфей» (приглушённая зелёная)
    val SAGE = ThemeSpec(
        id = "sage",
        name = "Шалфей",
        dark = false,
        surface = 0xFFF0F4F1L,
        card = 0xFFFAFCFAL,
        ink = 0xFF15261CL,
        inkSoft = 0xFF4C6154L,
        accent = 0xFF1F6E43L,
        onAccent = 0xFFFFFFFFL,
        line = 0xFFD7E2DAL,
        glassAlpha = 0.85f,
        glassBlurDp = 16f,
        blobA = 0xFFD5E8DCL,
        blobB = 0xFFE5EDE6L,
        blobC = 0xFFC8E0D0L,
    )

    // 6. «Киберпанк» (высококонтрастная тёмная с неоном)
    val CYBERPUNK = ThemeSpec(
        id = "cyberpunk",
        name = "Киберпанк",
        dark = true,
        surface = 0xFF0A0A0DL,
        card = 0xFF14141AL,
        ink = 0xFFF8F9FAL,
        inkSoft = 0xFFA0A5B5L,
        accent = 0xFF00E5FFL,
        onAccent = 0xFF00151CL,
        line = 0xFF262733L,
        glassAlpha = 0.85f,
        glassBlurDp = 16f,
        blobA = 0xFF0D253AL,
        blobB = 0xFF2A0D30L,
        blobC = 0xFF0B332FL,
    )

    val CURATED: List<ThemeSpec> = listOf(AS_IS, GRAPHITE, WARM_PAPER, NORDIC, SAGE, CYBERPUNK)
    val LIGHT: List<ThemeSpec> = CURATED.filter { !it.dark }
    val DARK: List<ThemeSpec> = CURATED.filter { it.dark }

    fun byId(id: String?, fallback: ThemeSpec = AS_IS): ThemeSpec =
        CURATED.firstOrNull { it.id == id } ?: fallback
}
