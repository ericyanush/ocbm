package com.carlink.usbhelper

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives and serves maneuver PNGs for GoogleTemplatesHost's cluster renderer.
 *
 * The Host expects this authority to accept `insert(iconId, data)`, return a content URI from
 * `query(selection=iconId)`, and serve the corresponding PNG from `openFile`. The Host's own
 * provider implementation is not registered on the GM build, so the sideloaded helper claims it.
 * Only system callers may insert or query icons.
 */
class ClusterIconProvider : ContentProvider() {
    private val pngByKey = lruMap<ByteArray>(MAX_CONTENT_ENTRIES)
    private val keyByIconId = lruMap<String>(MAX_ICON_ID_ENTRIES)
    private val scaledPng = lruMap<ByteArray>(MAX_SCALED_ENTRIES)
    private val systemCallers = ConcurrentHashMap<String, Boolean>()

    override fun onCreate(): Boolean {
        Log.i(TAG, "registered authority=$AUTHORITY")
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val unknown = Uri.parse("content://$AUTHORITY/img/unknown")
        if (!callerIsSystem("insert")) return unknown
        val iconId = values?.getAsString("iconId")
        val png = values?.getAsByteArray("data")
        if (iconId.isNullOrBlank() || png == null || png.size > MAX_PNG_BYTES || !isPng(png)) {
            Log.w(TAG, "insert refused iconId=$iconId size=${png?.size} validPng=${png?.let(::isPng)}")
            return unknown
        }
        val key = contentKey(png)
        pngByKey[key] = png
        keyByIconId[iconId] = key
        return contentUri(key)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("contentUri", "aspectRatio"))
        if (selection == null || !callerIsSystem("query")) return cursor
        val png = keyByIconId[selection]?.let { pngByKey[it] } ?: return cursor
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        val aspect = if (bounds.outHeight > 0) bounds.outWidth.toDouble() / bounds.outHeight else 1.0
        cursor.addRow(arrayOf<Any>(contentUri(contentKey(png)).toString(), aspect))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val key = uri.lastPathSegment ?: return null
        val source = pngByKey[key] ?: return null
        val width = uri.getQueryParameter("w")?.toIntOrNull()
        val height = uri.getQueryParameter("h")?.toIntOrNull()
        val data = if (width != null && height != null &&
            width in 1..MAX_SCALED_PX && height in 1..MAX_SCALED_PX
        ) {
            scalePng(source, width, height)
        } else {
            source
        }
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(data) }
            }.onFailure { Log.w(TAG, "openFile pipe failed key=$key: ${it.message}") }
        }, "ClusterIconPipe").apply { isDaemon = true }.start()
        return pipe[0]
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun callerIsSystem(operation: String): Boolean {
        val caller = runCatching { callingPackage }.getOrNull() ?: return false
        return systemCallers.getOrPut(caller) {
            val flags = runCatching { context?.packageManager?.getApplicationInfo(caller, 0)?.flags }.getOrNull() ?: 0
            val allowed = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            Log.i(TAG, "$operation caller=$caller allowed=$allowed")
            allowed
        }
    }

    private fun contentUri(key: String): Uri = Uri.parse("content://$AUTHORITY/img/$key")

    private fun contentKey(png: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(png).take(8).joinToString(prefix = "png_") { "%02x".format(it) }

    private fun scalePng(png: ByteArray, width: Int, height: Int): ByteArray = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth !in 1..MAX_SOURCE_PX || bounds.outHeight !in 1..MAX_SOURCE_PX) return png
        val cacheKey = "${contentKey(png)}@${width}x$height"
        scaledPng[cacheKey]?.let { return it }
        val source = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return png
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
        if (scaled !== source) scaled.recycle()
        source.recycle()
        output.toByteArray().also { scaledPng[cacheKey] = it }
    } catch (error: Exception) {
        Log.w(TAG, "scalePng failed; serving source: ${error.message}")
        png
    } catch (error: OutOfMemoryError) {
        Log.w(TAG, "scalePng out of memory; serving source")
        png
    }

    private fun isPng(bytes: ByteArray): Boolean = bytes.size >= PNG_SIGNATURE.size &&
        PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    private fun <V> lruMap(limit: Int): MutableMap<String, V> = Collections.synchronizedMap(
        object : LinkedHashMap<String, V>(limit, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > limit
        },
    )

    private companion object {
        const val TAG = "CarlinkClusterIcons"
        const val AUTHORITY = "com.google.android.apps.automotive.templates.host.ClusterIconContentProvider"
        const val MAX_CONTENT_ENTRIES = 20
        const val MAX_ICON_ID_ENTRIES = 256
        const val MAX_SCALED_ENTRIES = 20
        const val MAX_PNG_BYTES = 512 * 1024
        const val MAX_SCALED_PX = 1024
        const val MAX_SOURCE_PX = 2048
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
