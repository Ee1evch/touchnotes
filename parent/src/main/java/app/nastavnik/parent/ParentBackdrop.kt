package app.nastavnik.parent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Свой фон-картинка приложения родителя: уменьшенная копия в файлах, поверх — лёгкая вуаль, чтобы текст читался. */
object ParentBackdrop {
    var version by mutableIntStateOf(0)
        private set

    fun file(c: Context) = File(c.filesDir, "backdrop.jpg")

    fun has(c: Context) = file(c).exists()

    suspend fun save(c: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
                val k = minOf(1f, 2400f / maxOf(info.size.width, info.size.height))
                d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            file(c).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            version++
            true
        }.getOrDefault(false)
    }

    fun remove(c: Context) {
        file(c).delete()
        version++
    }
}

/** Фон всех экранов родителя: своя картинка (если выбрана) или цвет темы. */
@Composable
fun BackdropBox(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(null, ParentBackdrop.version) {
        value = withContext(Dispatchers.IO) {
            ParentBackdrop.file(context).takeIf { it.exists() }?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        img?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = if (isSystemInDarkTheme()) 0.45f else 0.35f)))
        }
        content()
    }
}
