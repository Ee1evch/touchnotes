package app.nastavnik.overlay

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.nastavnik.R

/** Шрифт Onest для окон поверх Touchnotes (там нет Compose-темы). */
object Fonts {
    private var regular: Typeface? = null
    private var medium: Typeface? = null
    private var semibold: Typeface? = null

    private fun load(ctx: Context, id: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(ctx, id) }.getOrNull() ?: fallback

    fun regular(ctx: Context): Typeface = regular ?: load(ctx, R.font.onest_regular, Typeface.DEFAULT).also { regular = it }
    fun medium(ctx: Context): Typeface = medium ?: load(ctx, R.font.onest_medium, Typeface.DEFAULT).also { medium = it }
    fun semibold(ctx: Context): Typeface = semibold ?: load(ctx, R.font.onest_semibold, Typeface.DEFAULT_BOLD).also { semibold = it }
}
