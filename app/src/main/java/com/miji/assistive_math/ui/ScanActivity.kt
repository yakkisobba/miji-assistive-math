package com.miji.assistive_math.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.miji.assistive_math.R
import com.miji.assistive_math.ui.BottomNavHelper
import com.miji.assistive_math.ui.BottomNavHelper.Tab
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SCAN SCREEN
 */
class ScanActivity : AppCompatActivity() {

    private var isFlashOn = false

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── Permission launchers ───────────────────────────────────────────────────

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showCameraDeniedMessage()
        }

    // Gallery picker — receives the URI of the image the user selected
    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { handleGalleryImage(it) }
        }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        setupSpeakingCard()
        setupShutterRow()
        setupBottomNav()
        setupTopBar()

        if (hasCameraPermission()) startCamera()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // ── Camera permission ──────────────────────────────────────────────────────

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    // ── Start Camera ───────────────────────────────────────────────────────────

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.cameraPreview)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        // TODO: hand imageProxy to YOLO detector
                        imageProxy.close()
                    }
                }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                showCameraDeniedMessage()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showCameraDeniedMessage() {
        updateSpeakingCard(
            "Camera permission is needed to scan equations. " +
                    "Please enable Camera access for MIJI in Settings."
        )
        setAutoCaptureStatus("CAMERA UNAVAILABLE")
    }

    // ── Top Bar ────────────────────────────────────────────────────────────────

    private fun setupTopBar() {
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            MenuHelper.showClassroomMenu(this)
        }
    }

    // ── Speaking card ──────────────────────────────────────────────────────────

    fun updateSpeakingCard(instruction: String) {
        findViewById<TextView>(R.id.tvSpeakingInstruction).text = instruction
    }

    private fun setupSpeakingCard() {
        // Initial state set via XML strings.
    }

    // ── Shutter Row ────────────────────────────────────────────────────────────

    private fun setupShutterRow() {

        // ── Flash toggle ───────────────────────────────────────────────────────
        val ivFlash = findViewById<ImageView>(R.id.ivFlash)
        findViewById<View>(R.id.btnFlash).setOnClickListener {
            isFlashOn = !isFlashOn
            camera?.cameraControl?.enableTorch(isFlashOn)

            // Tint icon accent when ON, white when OFF
            val tint = if (isFlashOn) getColor(R.color.accent) else getColor(R.color.white)
            ivFlash.setColorFilter(tint)
        }

        // ── Shutter capture ────────────────────────────────────────────────────
        findViewById<FrameLayout>(R.id.btnShutter).setOnClickListener {
            capturePhoto()
        }

        // ── Gallery upload ─────────────────────────────────────────────────────
        findViewById<View>(R.id.btnUpload).setOnClickListener {
            galleryLauncher.launch("image/*")
        }
    }

    // ── Photo capture ──────────────────────────────────────────────────────────

    private fun capturePhoto() {
        val capture = imageCapture ?: run {
            Log.w(TAG, "capturePhoto: imageCapture not ready")
            return
        }

        // Save to app's cache directory
        val photoFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        setAutoCaptureStatus("CAPTURING…")

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = Uri.fromFile(photoFile)
                    Log.d(TAG, "Photo saved: $uri")
                    setAutoCaptureStatus("PROCESSING…")
                    // TODO: pass uri to your detector / socket layer
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed: ${exception.message}", exception)
                    setAutoCaptureStatus("AUTO-CAPTURE READY")
                    updateSpeakingCard("Capture failed. Please try again.")
                }
            }
        )
    }

    // ── Gallery result ─────────────────────────────────────────────────────────

    private fun handleGalleryImage(uri: Uri) {
        Log.d(TAG, "Gallery image selected: $uri")
        setAutoCaptureStatus("PROCESSING…")
        // TODO: pass uri to your detector / socket layer
    }

    // ── Auto-capture state ─────────────────────────────────────────────────────

    fun setAutoCaptureStatus(status: String) {
        findViewById<TextView>(R.id.tvAutoCaptureLabel).text = status
    }

    // ── Bottom navigation ──────────────────────────────────────────────────────

    private fun setupBottomNav() {
        val nav = findViewById<View>(R.id.bottomNavScan)
        BottomNavHelper.bind(
            navRoot   = nav,
            activeTab = Tab.SCAN,
            onHome    = { startActivity(Intent(this, HomeActivity::class.java)) },
            onScan    = { /* already here */ },
            onProfile = { startActivity(Intent(this, ProfileActivity::class.java)) }
        )
    }

    companion object {
        private const val TAG = "ScanActivity"
    }
}