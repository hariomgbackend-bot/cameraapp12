package com.example.gallerycamera

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.gallerycamera.databinding.ActivityMainBinding
import java.io.File
import java.util.concurrent.Executors

/** Instructions plus two self-tests that fire a real IMAGE_CAPTURE intent at the system. */
class MainActivity : AppCompatActivity() {

    private enum class Mode { NONE, FILE, THUMB }

    private lateinit var b: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private var mode = Mode.NONE
    private val outFile get() = File(cacheDir, "selftest/out.jpg")

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { handle(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.testFull.setOnClickListener { run(Mode.FILE) }
        b.testThumb.setOnClickListener { run(Mode.THUMB) }
        b.defaults.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); io.shutdown() }

    private fun run(m: Mode) {
        mode = m
        b.result.text = ""
        b.resultImage.visibility = android.view.View.GONE
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (m == Mode.FILE) {
            outFile.parentFile?.mkdirs()
            outFile.delete()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.clipData = ClipData.newRawUri("", uri)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            b.result.text = "No app handles IMAGE_CAPTURE on this device."
        }
    }

    private fun handle(r: ActivityResult) {
        if (r.resultCode != RESULT_OK) {
            b.result.text = "Cancelled (RESULT_CANCELED). Expected if you backed out."
            return
        }
        when (mode) {
            Mode.FILE -> {
                val f = outFile
                if (!f.exists() || f.length() == 0L) {
                    b.result.text = "RESULT_OK but the output file is empty. The chosen camera app did not write to EXTRA_OUTPUT (probably not Gallery Camera)."
                    return
                }
                io.execute {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, o)
                    val bmp = runCatching { ImageUtils.decode(f, 1200) }.getOrNull()
                    runOnUiThread {
                        b.result.text = "RESULT_OK. EXTRA_OUTPUT file: ${o.outWidth}×${o.outHeight}, ${f.length() / 1024} KB, ${o.outMimeType}."
                        bmp?.let { b.resultImage.setImageBitmap(it); b.resultImage.visibility = android.view.View.VISIBLE }
                    }
                }
            }
            Mode.THUMB -> {
                val data = r.data
                val thumb: Bitmap? = try {
                    if (Build.VERSION.SDK_INT >= 33) data?.extras?.getParcelable("data", Bitmap::class.java)
                    else @Suppress("DEPRECATION") data?.extras?.getParcelable("data") as? Bitmap
                } catch (_: Exception) { null }
                val uri: Uri? = data?.data
                val sb = StringBuilder("RESULT_OK.\n")
                sb.append(if (thumb != null) "Thumbnail in \"data\": ${thumb.width}×${thumb.height}.\n" else "No thumbnail in \"data\".\n")
                if (uri != null) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } }
                    sb.append("Full image URI readable: ${o.outWidth}×${o.outHeight}, ${o.outMimeType}.")
                } else sb.append("No image URI returned.")
                b.result.text = sb.toString()
                thumb?.let { b.resultImage.setImageBitmap(it); b.resultImage.visibility = android.view.View.VISIBLE }
            }
            Mode.NONE -> Unit
        }
    }
}
