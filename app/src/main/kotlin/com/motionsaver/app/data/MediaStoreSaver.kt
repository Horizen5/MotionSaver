package com.motionsaver.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/** 落盘目标目录，四类内容各归其位，相册/音乐/下载管理器都能直接扫到。 */
enum class SaveCategory { IMAGE, VIDEO, AUDIO, TEXT }

/**
 * 把成品写入系统媒体库：小米相册才会扫描到动态照片并识别 XMP 元数据。
 * Android 10+ 走 MediaStore（分区存储，无需权限）；Android 8/9 走公共目录 + 媒体扫描。
 */
class MediaStoreSaver(private val context: Context) {

    companion object { private const val ALBUM = "MotionSaver" }

    fun save(file: File, displayName: String, mime: String, category: SaveCategory): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveQ(file, displayName, mime, category)
        } else {
            saveLegacy(file, displayName, mime, category)
        }

    private fun targetOf(category: SaveCategory): Pair<Uri, String> = when (category) {
        SaveCategory.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            "${Environment.DIRECTORY_PICTURES}/$ALBUM"
        SaveCategory.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            "${Environment.DIRECTORY_MOVIES}/$ALBUM"
        SaveCategory.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            "${Environment.DIRECTORY_MUSIC}/$ALBUM"
        SaveCategory.TEXT -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM"
    }

    private fun saveQ(file: File, name: String, mime: String, category: SaveCategory): String {
        val (collection, relPath) = targetOf(category)
        val existing = findExisting(collection, name)
        val uri: Uri = existing ?: context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("无法创建媒体库条目")

        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            FileInputStream(file).use { it.copyTo(out, 64 * 1024) }
        } ?: error("无法写入媒体库")

        if (existing == null) {
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return "$relPath/$name"
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

    private fun saveLegacy(file: File, name: String, mime: String, category: SaveCategory): String {
        val root = when (category) {
            SaveCategory.IMAGE -> Environment.DIRECTORY_PICTURES
            SaveCategory.VIDEO -> Environment.DIRECTORY_MOVIES
            SaveCategory.AUDIO -> Environment.DIRECTORY_MUSIC
            SaveCategory.TEXT -> Environment.DIRECTORY_DOWNLOADS
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(root), ALBUM)
        dir.mkdirs()
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(dest.absolutePath), arrayOf(mime), null
        )
        return dest.absolutePath
    }
}
