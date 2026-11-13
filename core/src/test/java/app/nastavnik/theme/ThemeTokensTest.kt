package app.nastavnik.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTokensTest {

    @Test
    fun curatedThemesCountAndIds() {
        assertEquals("Ровно 6 курируемых тем", 6, ThemeTokens.CURATED.size)
        assertEquals(4, ThemeTokens.LIGHT.size)
        assertEquals(2, ThemeTokens.DARK.size)

        // Обратная совместимость с текущей версией
        assertEquals("paper", ThemeTokens.AS_IS.id)
        assertEquals("graphite", ThemeTokens.GRAPHITE.id)

        val ids = ThemeTokens.CURATED.map { it.id }.toSet()
        assertEquals(setOf("paper", "graphite", "warm_paper", "nordic", "sage", "cyberpunk"), ids)
    }

    @Test
    fun wcagAaComplianceForCuratedThemes() {
        for (theme in ThemeTokens.CURATED) {
            val inkSurfRatio = ContrastRatio.calculate(theme.ink, theme.surface)
            val inkCardRatio = ContrastRatio.calculate(theme.ink, theme.card)
            val softCardRatio = ContrastRatio.calculate(theme.inkSoft, theme.card)
            val accentRatio = ContrastRatio.calculate(theme.onAccent, theme.accent)

            assertTrue(
                "Тема [${theme.name}]: ink на surface должен проходить WCAG AA (>= 4.5), факт: $inkSurfRatio",
                inkSurfRatio >= 4.5,
            )
            assertTrue(
                "Тема [${theme.name}]: ink на card должен проходить WCAG AA (>= 4.5), факт: $inkCardRatio",
                inkCardRatio >= 4.5,
            )
            assertTrue(
                "Тема [${theme.name}]: inkSoft на card должен проходить WCAG AA (>= 4.5), факт: $softCardRatio",
                softCardRatio >= 4.5,
            )
            assertTrue(
                "Тема [${theme.name}]: onAccent на accent должен проходить WCAG AA (>= 4.5), факт: $accentRatio",
                accentRatio >= 4.5,
            )
        }
    }

    @Test
    fun byIdFallback() {
        assertEquals(ThemeTokens.AS_IS, ThemeTokens.byId("paper"))
        assertEquals(ThemeTokens.GRAPHITE, ThemeTokens.byId("graphite"))
        assertEquals(ThemeTokens.CYBERPUNK, ThemeTokens.byId("cyberpunk"))
        assertEquals(ThemeTokens.AS_IS, ThemeTokens.byId("unknown_theme"))
        assertEquals(ThemeTokens.GRAPHITE, ThemeTokens.byId(null, fallback = ThemeTokens.GRAPHITE))
    }
}
