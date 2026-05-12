package com.miji.assistive_math.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object DebugImageSaver {

    private const val TAG = "DebugImageSaver"

    fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        fileName: String
    ) {
        try {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "debug_crops"
            )

            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, fileName)

            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.flush()
            }

            Log.d(TAG, "Saved debug image: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug image: $fileName", e)
        }
    }
}