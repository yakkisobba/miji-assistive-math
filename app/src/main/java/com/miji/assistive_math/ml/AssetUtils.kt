package com.miji.assistive_math.ml

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

fun assetFilePath(context: Context, assetName: String): String {
    val file = File(context.filesDir, assetName)

    if (file.exists()) {
        val deleted = file.delete()
        Log.d("AssetUtils", "Deleted old copied asset: $deleted")
    }

    context.assets.open(assetName).use { inputStream ->
        FileOutputStream(file).use { outputStream ->
            val buffer = ByteArray(4 * 1024)
            var read: Int

            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }

            outputStream.flush()
        }
    }

    Log.d(
        "AssetUtils",
        "Copied $assetName to ${file.absolutePath}, size=${file.length()} bytes"
    )

    return file.absolutePath
}