package dev.macdroid.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Renders an installed app's launcher icon to a PNG for the Mac (cached per package). */
object AppIcons {
    private const val SIZE_PX = 128
    private val cache = mutableMapOf<String, String?>()

    fun pngBase64(context: Context, pkg: String): String? = cache.getOrPut(pkg) {
        try {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, SIZE_PX, SIZE_PX)
            drawable.draw(Canvas(bitmap))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLog.i("No icon for $pkg: ${e.message}")
            null
        }
    }
}
