package app.nastavnik.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.nastavnik.app

/** Откуда взять страницы: камера, галерея, PDF. Разрешений не нужно — всё через системные окна. */
class Importers(val camera: () -> Unit, val gallery: () -> Unit, val pdf: () -> Unit)

@Composable
fun rememberImporters(onImages: (List<Uri>) -> Unit, onPdf: (Uri) -> Unit): Importers {
    val context = LocalContext.current
    val images by rememberUpdatedState(onImages)
    val pdfs by rememberUpdatedState(onPdf)
    var pendingCamera by rememberSaveable { mutableStateOf<Uri?>(null) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingCamera
        if (ok && u != null) images(listOf(u))
        pendingCamera = null
    }
    val gal = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) images(uris)
    }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { pdfs(it) } }
    return remember {
        Importers(
            camera = {
                val f = context.app.notebooks.cameraFile()
                val u = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                pendingCamera = u
                runCatching { cam.launch(u) }
            },
            gallery = { runCatching { gal.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
            pdf = { runCatching { doc.launch(arrayOf("application/pdf")) } },
        )
    }
}
