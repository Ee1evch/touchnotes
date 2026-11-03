package app.nastavnik.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nastavnik.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Свои картинки: фон приложения и фото преподавателей. Хранятся уменьшенными копиями в файлах приложения. */
object CustomImages {
    /** Растёт при каждой смене картинки — экраны перечитывают. */
    var version by mutableIntStateOf(0)
        private set

    fun backdropFile(c: Context) = File(c.filesDir, "custom/backdrop.jpg")

    private fun teacherKey(name: String) =
        MessageDigest.getInstance("SHA-1").digest(name.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun teacherFile(c: Context, name: String) = File(c.filesDir, "custom/teachers/${teacherKey(name)}.jpg")

    /** Сохранить картинку уменьшенной (длинная сторона ≤ [maxSide]). */
    suspend fun save(c: Context, uri: Uri, to: File, maxSide: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
                val k = minOf(1f, maxSide.toFloat() / maxOf(info.size.width, info.size.height))
                d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            to.parentFile?.mkdirs()
            val tmp = File(to.path + ".tmp")
            tmp.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            src.recycle()
            if (!tmp.renameTo(to)) { to.delete(); tmp.renameTo(to) }
            version++
            true
        }.getOrDefault(false)
    }

    fun remove(f: File) {
        f.delete()
        version++
    }

    suspend fun load(f: File): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!f.exists()) null else runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
    }
}

/**
 * Фото преподавателя в расписании — только если в настройках включено и фото есть (без пустых кружков).
 * Из нескольких преподавателей пары — первый, у кого есть фото.
 */
@Composable
fun TeacherAvatar(teachers: List<String>, size: Dp = 30.dp) {
    val context = LocalContext.current
    val on = context.app.settings.teacherPhotos()
    val img by produceState<ImageBitmap?>(null, teachers, on, CustomImages.version) {
        value = if (!on) null else teachers.firstNotNullOfOrNull { t -> CustomImages.load(CustomImages.teacherFile(context, t)) }
    }
    img?.let { Image(it, contentDescription = "Фото преподавателя", modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop) }
}
