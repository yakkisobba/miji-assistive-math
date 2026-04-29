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
    private var isCapturing = false
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── Permission launchers ───────────────────────────────────────────────────

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showCameraDeniedMessage()
        }

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

            // ImageAnalysis — frames will be handed to YOLO detector by backend team
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        // TODO: backend team wires YOLO detector here
                        // They should call updateGuidance() with the result
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

    // ── Guidance System ────────────────────────────────────────────────────────

    /**
     * Called by the YOLO detector (backend team) with a direction string.
     * Accepted values:
     *   "move_left"  / "move_right" / "move_up" / "move_down"
     *   "hold_still" → triggers auto-capture
     *   "capturing"  → currently capturing
     *   anything else → show searching state
     */
    fun updateGuidance(direction: String) {
        val cue = when (direction) {
            "move_left"  -> GuidanceCue("Move camera to the left",          "ADJUST")
            "move_right" -> GuidanceCue("Move camera to the right",         "ADJUST")
            "move_up"    -> GuidanceCue("Move camera up",                   "ADJUST")
            "move_down"  -> GuidanceCue("Move camera down",                 "ADJUST")
            "move_up_left"    -> GuidanceCue("Move camera up and to the left",   "ADJUST")
            "move_up_right"   -> GuidanceCue("Move camera up and to the right",  "ADJUST")
            "move_down_left"  -> GuidanceCue("Move camera down and to the left", "ADJUST")
            "move_down_right" -> GuidanceCue("Move camera down and to the right","ADJUST")
            "hold_still" -> GuidanceCue("Hold still. Capturing equation…",  "CAPTURING", true)
            "capturing"  -> GuidanceCue("Capturing equation…",              "CAPTURING…")
            else         -> GuidanceCue("Point camera at an equation",       "SEARCHING…")
        }
        runOnUiThread { applyGuidance(cue) }
    }

    /**
     * Guidance cue data class.
     * [direction]  — human-readable instruction shown in speaking card
     * [status]     — short label shown in AUTO-CAPTURE status
     * [isCentered] — true when equation is centered and ready to capture
     */
    data class GuidanceCue(
        val direction: String,
        val status: String,
        val isCentered: Boolean = false
    )

    /**
     * Pushes guidance cue to UI and triggers auto-capture when centered.
     */
    private fun applyGuidance(cue: GuidanceCue) {
        updateSpeakingCard(cue.direction)
        setAutoCaptureStatus(cue.status)

        if (cue.isCentered && !isCapturing) {
            capturePhoto()
        }
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
        val ivFlash = findViewById<ImageView>(R.id.ivFlash)

        // Flash toggle
        findViewById<View>(R.id.btnFlash).setOnClickListener {
            isFlashOn = !isFlashOn
            camera?.cameraControl?.enableTorch(isFlashOn)
            val tint = if (isFlashOn) getColor(R.color.accent) else getColor(R.color.white)
            ivFlash.setColorFilter(tint)
        }

        // Shutter — manual capture
        findViewById<FrameLayout>(R.id.btnShutter).setOnClickListener {
            capturePhoto()
        }

        // Upload — gallery picker
        findViewById<View>(R.id.btnUpload).setOnClickListener {
            galleryLauncher.launch("image/*")
        }
    }

    // ── Photo capture ──────────────────────────────────────────────────────────

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        if (isCapturing) return

        isCapturing = true
        setAutoCaptureStatus("CAPTURING…")
        updateSpeakingCard("Hold still. Capturing equation…")

        val photoFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = Uri.fromFile(photoFile)
                    Log.d(TAG, "Photo saved: $uri")
                    isCapturing = false
                    setAutoCaptureStatus("PROCESSING…")
                    updateSpeakingCard("Processing equation…")
                    // TODO: pass uri to YOLO detector / socket layer
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed: ${exception.message}", exception)
                    isCapturing = false
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
        updateSpeakingCard("Processing selected image…")
        // TODO: pass uri to YOLO detector / socket layer
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