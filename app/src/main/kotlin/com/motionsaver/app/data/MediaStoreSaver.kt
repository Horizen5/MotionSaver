package com.motionsaver.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * 把成品写入系统相册，这样小米相册才会扫描到动态照片并识别 XMP 元数据。
 * Android 10+ 走 MediaStore（分区存储，无需权限）；Android 8/9 走公共 Pictures 目录 + 媒体扫描。
 */
class MediaStoreSaver(private val context: Context) {

    companion object {
        private const val ALBUM = "MotionSaver"
    }

    /** @return 展示给用户的路径或 content Uri 描述 */
    fun save(file: File, displayName: String, mime: String, isVideo: Boolean): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveQ(file, displayName, mime, isVideo)
        } else {
            saveLegacy(file, displayName)
        }
    }

    private fun saveQ(file: File, name: String, mime: String, isVideo: Boolean): String {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        // 同名文件已存在则复用，避免重复插入
        val existing = findExisting(collection, name)
        val uri: Uri = existing ?: context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("无法创建相册条目")

        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            FileInputStream(file).use { it.copyTo(out, 64 * 1024) }
        } ?: error("无法写入相册")

        if (existing == null) {
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return "${Environment.DIRECTORY_PICTURES}/$ALBUM/$name"
    }

    private fun findExisting(collection: Uri, name: String): Uri? {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name), null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return android.content.ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    private fun saveLegacy(file: File, name: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM
        )
        dir.mkdirs()
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null
        )
        return dest.absolutePath
    }
}
