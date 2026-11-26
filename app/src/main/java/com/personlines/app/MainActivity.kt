package com.personlines.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var frameView: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var permissionBox: View
    private lateinit var btnSwitch: Button
    private lateinit var btnVideo: Button
    private lateinit var btnPlay: Button
    private lateinit var btnCamera: Button

    private val executor = Executors.newSingleThreadExecutor()
    private val detector by lazy { PersonDetector(applicationContext) }
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile private var lensFacing = CameraSelector.LENS_FACING_BACK
    @Volatile private var selected: Set<Part> = setOf(Part.BRAZOS, Part.PIERNAS)
    @Volatile private var videoMode = false
    @Volatile private var playing = true
    @Volatile private var session = 0
    private var lastError: String? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else permissionBox.visibility = View.VISIBLE
        }

    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) startVideo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        frameView = findViewById(R.id.frameView)
        overlay = findViewById(R.id.overlay)
        permissionBox = findViewById(R.id.permissionBox)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnVideo = findViewById(R.id.btnVideo)
        btnPlay = findViewById(R.id.btnPlay)
        btnCamera = findViewById(R.id.btnCamera)

        setupChips()

        btnSwitch.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            overlay.show(null)
            bindCamera()
        }
        btnVideo.setOnClickListener { videoPicker.launch("video/*") }
        btnCamera.setOnClickListener { backToCamera() }
        btnPlay.setOnClickListener {
            playing = !playing
            btnPlay.text = if (playing) "Pausa" else "Seguir"
        }
        findViewById<Button>(R.id.btnPermission).setOnClickListener {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        ensurePermission()
    }

    override fun onResume() {
        super.onResume()
        if (!videoMode && cameraProvider == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    override fun onDestroy() {
        session++
        executor.shutdown()
        super.onDestroy()
    }

    private fun setupChips() {
        val chips = findViewById<ChipGroup>(R.id.chips)
        for (p in Part.values()) {
            val chip = Chip(this)
            chip.text = p.label
            chip.isCheckable = true
            chip.isChecked = p in selected
            chip.setOnCheckedChangeListener { _, checked ->
                selected = if (checked) selected + p else selected - p
                if (selected.isEmpty()) overlay.show(null)
            }
            chips.addView(chip)
        }
    }

    // Permiso de cámara
    private fun ensurePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        permissionBox.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            if (!videoMode) bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("DEPRECATION")
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            provider.bindToLifecycle(this, selector, preview, analysis)
        } catch (e: Throwable) {
            reportError(e)
        }
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (videoMode) return
            val parts = selected
            if (parts.isEmpty()) {
                overlay.show(null)
                return
            }
            val raw = proxy.toBitmap()
            val bmp = prepBitmap(raw, proxy.imageInfo.rotationDegrees)
            val det = detector.detect(bmp, parts)
            det.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
            det.fit = false
            overlay.show(det)
        } catch (e: Throwable) {
            reportError(e)
        } finally {
            proxy.close()
        }
    }

    // Modo video
    private fun startVideo(uri: Uri) {
        session++
        val my = session
        videoMode = true
        playing = true
        cameraProvider?.unbindAll()
        previewView.visibility = View.GONE
        frameView.visibility = View.VISIBLE
        overlay.show(null)
        btnSwitch.visibility = View.GONE
        btnCamera.visibility = View.VISIBLE
        btnPlay.visibility = View.VISIBLE
        btnPlay.text = "Pausa"

        executor.execute {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(this, uri)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var t = 0L
                while (session == my) {
                    val t0 = SystemClock.uptimeMillis()
                    val raw = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (raw == null) {
                        if (t == 0L) {
                            runOnUiThread {
                                Toast.makeText(this, "No pude leer el video", Toast.LENGTH_LONG).show()
                            }
                            break
                        }
                        t = 0L
                        continue
                    }
                    val bmp = prepBitmap(raw, 0)
                    val parts = selected
                    var det: Detection? = null
                    if (parts.isNotEmpty()) {
                        try {
                            det = detector.detect(bmp, parts)
                            det.mirror = false
                            det.fit = true
                        } catch (e: Throwable) {
                            reportError(e)
                        }
                    }
                    val d = det
                    runOnUiThread {
                        if (session == my) {
                            frameView.setImageBitmap(bmp)
                            overlay.show(d)
                        }
                    }
                    val e = SystemClock.uptimeMillis() - t0
                    if (playing) {
                        t += maxOf(e, 66L)
                        if (dur > 0 && t >= dur) t = 0L
                    }
                    if (e < 66L) Thread.sleep(66L - e)
                    if (!playing) Thread.sleep(150L)
                }
            } catch (e: Throwable) {
                reportError(e)
            } finally {
                r.release()
            }
        }
    }

    private fun backToCamera() {
        session++
        videoMode = false
        frameView.setImageDrawable(null)
        frameView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        overlay.show(null)
        btnSwitch.visibility = View.VISIBLE
        btnCamera.visibility = View.GONE
        btnPlay.visibility = View.GONE
        if (cameraProvider != null) bindCamera() else ensurePermission()
    }

    private fun reportError(e: Throwable) {
        Log.e("PersonLines", "error", e)
        val msg = e.message ?: e.javaClass.simpleName
        if (msg != lastError) {
            lastError = msg
            runOnUiThread {
                Toast.makeText(this, "Error: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
