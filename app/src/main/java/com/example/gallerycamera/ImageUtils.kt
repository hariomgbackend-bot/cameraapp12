package com.example.gallerycamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** An error whose message is safe to show to the user as-is. */
class AppException(message: String, cause: Throwable? = null) : IOException(message, cause)

object ImageUtils {
    private const val BUF = 64 * 1024
    private const val MIN_FREE_BYTES = 8L * 1024 * 1024

    private fun querySize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (_: Exception) { null }

    private fun requireSpace(dir: File, needed: Long) {
        dir.mkdirs()
        if (dir.usableSpace < needed + MIN_FREE_BYTES) {
            throw AppException("Not enough free storage to copy this photo. Free up some space and try again.")
        }
    }

    /** Returns the MIME type if [file] is a decodable image, else null. */
    fun sniffMime(file: File): String? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outMimeType else null
    }

    /**
     * Byte-for-byte copy of [source] into [dest] (no re-encoding). Written to a temp file first,
     * so a failure never leaves a half-written [dest]. Returns the detected MIME type.
     */
    fun importToFile(context: Context, source: Uri, dest: File): String {
        val dir = dest.parentFile ?: throw AppException("Internal storage error.")
        requireSpace(dir, querySize(context, source) ?: 0L)
        val tmp = File(dir, dest.name + ".tmp")
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: throw FileNotFoundException("Could not open the selected file.")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, BUF) } }
            val mime = sniffMime(tmp) ?: throw AppException("That file isn't an image Android can read.")
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not store the selected image.")
            return mime
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    fun copyFile(src: File, dest: File) {
        requireSpace(dest.parentFile ?: throw AppException("Internal storage error."), src.length())
        src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, BUF) } }
    }

    private fun exifMatrix(file: File): Matrix? {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(-90f)
            else -> return null
        }
        return m
    }

    /** Decodes [file] with EXIF rotation applied, longest edge <= [maxEdge] (Int.MAX_VALUE = full size). */
    fun decode(file: File, maxEdge: Int): Bitmap {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxEdge != Int.MAX_VALUE && max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            var bmp = BitmapFactory.decodeFile(
                file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: throw AppException("That image couldn't be decoded.")
            val longest = max(bmp.width, bmp.height)
            if (maxEdge != Int.MAX_VALUE && longest > maxEdge) {
                val s = maxEdge / longest.toFloat()
                bmp = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * s).roundToInt().coerceAtLeast(1),
                    (bmp.height * s).roundToInt().coerceAtLeast(1), true
                )
            }
            exifMatrix(file)?.let { bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, it, true) }
            return bmp
        } catch (e: OutOfMemoryError) {
            throw AppException("This image is too large to process on this device.", e)
        }
    }

    private fun openOut(context: Context, dest: Uri): java.io.OutputStream {
        val cr = context.contentResolver
        return try {
            cr.openOutputStream(dest, "wt")
        } catch (_: FileNotFoundException) {
            cr.openOutputStream(dest, "w")      // some providers reject "wt"
        } catch (_: IllegalArgumentException) {
            cr.openOutputStream(dest, "w")
        } ?: throw AppException("The requesting app didn't provide a writable location.")
    }

    /**
     * Writes the picked image to the URI the requesting app supplied in EXTRA_OUTPUT.
     * Raw bytes are copied unless the destination is declared image/jpeg and the source is not,
     * in which case it is converted to JPEG at quality 100 so the file matches its name/type.
     */
    fun writeToDestination(context: Context, src: File, srcMime: String, dest: Uri) {
        val destMime = try { context.contentResolver.getType(dest) } catch (_: Exception) { null }
        val convert = destMime.equals("image/jpeg", ignoreCase = true) && srcMime != "image/jpeg"
        try {
            openOut(context, dest).use { out ->
                if (convert) {
                    val bmp = decode(src, Int.MAX_VALUE)
                    try { bmp.compress(Bitmap.CompressFormat.JPEG, 100, out) } finally { bmp.recycle() }
                } else {
                    src.inputStream().use { it.copyTo(out, BUF) }
                }
            }
        } catch (e: SecurityException) {
            throw AppException("The requesting app didn't allow writing to its output location.", e)
        }
    }

    fun extensionFor(mime: String) = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heif", "image/heic" -> "heic"
        "image/bmp" -> "bmp"
        else -> "img"
    }
}
