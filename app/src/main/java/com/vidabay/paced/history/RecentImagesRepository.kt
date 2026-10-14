package com.vidabay.paced.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecentImage(
    val id: String,
    val timestamp: String,
    val previewFile: File,
    val payloadFile: File
) {
    fun loadBitmap(): Bitmap? {
        return if (previewFile.exists()) {
            BitmapFactory.decodeFile(previewFile.absolutePath)
        } else {
            null
        }
    }

    fun loadPayload(): ByteArray? {
        return if (payloadFile.exists()) {
            payloadFile.readBytes()
        } else {
            null
        }
    }
}

object RecentImagesRepository {

    private const val MAX_RECENT_IMAGES = 10
    private const val DIR_NAME = "recent_images"

    fun getRecentImages(context: Context): List<RecentImage> {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return emptyList()

        val previewFiles = dir.listFiles { _, name -> name.endsWith("_preview.png") } ?: return emptyList()

        return previewFiles.mapNotNull { previewFile ->
            val id = previewFile.name.removeSuffix("_preview.png")
            val payloadFile = File(dir, "${id}_payload.bin")
            if (payloadFile.exists()) {
                val timeMillis = id.toLongOrNull() ?: previewFile.lastModified()
                val formattedTime = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timeMillis))
                RecentImage(id, formattedTime, previewFile, payloadFile)
            } else {
                null
            }
        }.sortedByDescending { it.previewFile.lastModified() }
    }

    fun saveRecentImage(context: Context, previewBitmap: Bitmap, payload: ByteArray): RecentImage {
        val dir = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
        val id = System.currentTimeMillis().toString()
        val previewFile = File(dir, "${id}_preview.png")
        val payloadFile = File(dir, "${id}_payload.bin")

        FileOutputStream(previewFile).use { out ->
            previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        payloadFile.writeBytes(payload)

        // Prune older than 10
        val all = getRecentImages(context)
        if (all.size > MAX_RECENT_IMAGES) {
            for (old in all.drop(MAX_RECENT_IMAGES)) {
                old.previewFile.delete()
                old.payloadFile.delete()
            }
        }

        val formattedTime = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
        return RecentImage(id, formattedTime, previewFile, payloadFile)
    }
}
