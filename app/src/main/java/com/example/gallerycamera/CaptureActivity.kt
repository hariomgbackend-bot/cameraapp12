package com.example.gallerycamera

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.gallerycamera.databinding.ActivityCaptureBinding
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors

/** Handles android.media.action.IMAGE_CAPTURE by letting the user pick an existing image. */
class CaptureActivity : AppCompatActivity() {

    private lateinit var b: ActivityCaptureBinding
    private val io = Executors.newSingleThreadExecutor()

    private var outputUri: Uri? = null
    private var picked: File? = null
    private var pickedMime: String? = null
    private var busy = false

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::importPicked)
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importPicked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setResult(RESULT_CANCELED)   // default outcome: back/swipe-away = cancelled

        val out = readOutputUri()
        if (out != null && !isAllowedOutput(out)) {
            Toast.makeText(this, "Rejected an unsafe output location.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        outputUri = out

        val who = callingActivity?.packageName ?: "Another app"
        b.requestInfo.text = if (out != null) {
            "$who asked for a camera photo. Your selection is saved to the location it provided."
        } else {
            "$who asked for a camera photo. It will receive a thumbnail and a link to the full image."
        }

        if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_FILE)?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    picked = f
                    pickedMime = savedInstanceState.getString(KEY_MIME)
                    showPreviewOf(f)
                }
            }
        } else {
            io.execute {
                File(cacheDir, "picked").deleteRecursively()
                pruneShared()
            }
        }

        b.selectPhoto.setOnClickListener {
            try {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (e: Exception) {
                showError("No photo picker is available on this device.")
            }
        }
        b.browseFiles.setOnClickListener {
            try {
                pickFile.launch(arrayOf("image/*"))
            } catch (e: Exception) {
                showError("No file browser is available on this device.")
            }
        }
        b.cancel.setOnClickListener { setResult(RESULT_CANCELED); finish() }
        b.confirm.setOnClickListener { confirm() }
        updateUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FILE, picked?.absolutePath)
        outState.putString(KEY_MIME, pickedMime)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---- Intent parsing -------------------------------------------------------------------

    private fun readOutputUri(): Uri? {
        val fromExtra = try {
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
            }
        } catch (_: Exception) { null }   // malformed extra
        if (fromExtra != null) return fromExtra
        // Some callers only pass the URI via ClipData (used to carry the permission grant)
        return try {
            intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } catch (_: Exception) { null }
    }

    /**
     * Never let a caller aim us at our own FileProvider files (we run with our own identity, so
     * we could otherwise overwrite them). Only the self-test folder is allowed.
     */
    private fun isAllowedOutput(uri: Uri): Boolean {
        if (uri.authority != "$packageName.fileprovider") return true
        return uri.pathSegments.firstOrNull() == "selftest"
    }

    // ---- Picking --------------------------------------------------------------------------

    private fun importPicked(uri: Uri) {
        setBusy(true)
        showError(null)
        val dir = File(cacheDir, "picked")
        val incoming = File(dir, "incoming")
        val current = File(dir, "current")
        val appCtx = applicationContext
        io.execute {
            val r = runCatching {
                val mime = ImageUtils.importToFile(appCtx, uri, incoming)
                val bmp = ImageUtils.decode(incoming, 1600)
                current.delete()
                if (!incoming.renameTo(current)) throw IOException("Could not store the selected image.")
                mime to bmp
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(false)
                r.onSuccess { (mime, bmp) ->
                    picked = current
                    pickedMime = mime
                    b.preview.setImageBitmap(bmp)
                    b.placeholder.visibility = View.GONE
                }.onFailure { showError(describe(it)) }
                updateUi()
            }
        }
    }

    private fun showPreviewOf(f: File) {
        io.execute {
            val r = runCatching { ImageUtils.decode(f, 1600) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { b.preview.setImageBitmap(it); b.placeholder.visibility = View.GONE }
                    .onFailure { picked = null; showError(describe(it)) }
                updateUi()
            }
        }
    }

    // ---- Delivering the result ------------------------------------------------------------

    private fun confirm() {
        val src = picked ?: return
        val mime = pickedMime ?: return
        if (busy) return
        val out = outputUri
        setBusy(true)
        showError(null)
        io.execute {
            val r = runCatching { deliver(src, mime, out) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(false)
                r.onSuccess { setResult(RESULT_OK, it); finish() }
                    .onFailure { showError(describe(it)) }
            }
        }
    }

    private fun deliver(src: File, mime: String, out: Uri?): Intent {
        val ctx = applicationContext
        if (out != null) {
            // EXTRA_OUTPUT flow: the platform camera returns no thumbnail here, so neither do we.
            ImageUtils.writeToDestination(ctx, src, mime, out)
            return Intent().setData(out)
        }
        // No EXTRA_OUTPUT: return a small bitmap in "data" (classic contract) AND a readable
        // content:// URI to the full-size image for apps that look at intent.data.
        val thumb = ImageUtils.decode(src, THUMB_EDGE)
        val shared = File(cacheDir, "shared")
        val copy = File(shared, "capture_${System.currentTimeMillis()}.${ImageUtils.extensionFor(mime)}")
        ImageUtils.copyFile(src, copy)
        val uri = FileProvider.getUriForFile(ctx, "$packageName.fileprovider", copy)
        return Intent()
            .putExtra("data", thumb)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun pruneShared() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        File(cacheDir, "shared").listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    // ---- UI helpers -----------------------------------------------------------------------

    private fun setBusy(value: Boolean) {
        busy = value
        b.progress.visibility = if (value) View.VISIBLE else View.GONE
        updateUi()
    }

    private fun updateUi() {
        b.selectPhoto.isEnabled = !busy
        b.browseFiles.isEnabled = !busy
        b.confirm.isEnabled = !busy && picked != null
    }

    private fun showError(msg: String?) {
        b.error.text = msg
        b.error.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun describe(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.toList()
        return when {
            t is AppException -> t.message ?: "Something went wrong."
            chain.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space left") == true } ->
                "Not enough free storage. Free up some space and try again."
            t is SecurityException -> "Access to that location was denied."
            t is FileNotFoundException -> "Couldn't open the file. It may have been moved or deleted."
            t is OutOfMemoryError -> "This image is too large to process on this device."
            else -> "Something went wrong: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private companion object {
        const val KEY_FILE = "picked_file"
        const val KEY_MIME = "picked_mime"
        const val THUMB_EDGE = 320   // ~400 KB ARGB worst case; safely under the ~1 MB Binder limit
    }
}
