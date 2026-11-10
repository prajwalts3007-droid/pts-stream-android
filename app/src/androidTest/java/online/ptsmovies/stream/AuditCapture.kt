package online.ptsmovies.stream

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Public test output survives AGP uninstalling the target APK after instrumentation. */
fun saveAuditCapture(name: String, bitmap: Bitmap) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/pts-native-audit")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        checkNotNull(context.contentResolver.openOutputStream(uri)).use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } else {
        val folder = File(context.getExternalFilesDir(null), "audit-screens").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
