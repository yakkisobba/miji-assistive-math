package com.miji.assistive_math.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.miji.assistive_math.R
import com.miji.assistive_math.ui.HomeActivity
import com.miji.assistive_math.ui.ProfileActivity
import com.miji.assistive_math.ui.BottomNavHelper
import com.miji.assistive_math.ui.BottomNavHelper.Tab
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SCAN SCREEN
 */

class ScanActivity : AppCompatActivity() {

    private var isFlashOn = false

    // Live CameraX handles, populated once startCamera() succeeds.
    //   camera        — used for flash/torch control
    //   imageCapture  — used by the shutter button to take a photo
    // Both are nullable because the camera might never start (denied permission,
    // no back camera, etc.), and click handlers should fail gracefully.
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    // Background thread for the YOLO frame analyzer. ImageAnalysis must run off
    // the main thread or the UI will jank at 30 fps. Single-threaded so frames
    // are processed in order without contention.
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Registers a callback for the system permission dialog. We don't show
    // the dialog here — we just declare what to do when the user responds.
    // .launch(...) (called below) is what actually shows the dialog.
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showCameraDeniedMessage()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        setupSpeakingCard()
        setupShutterRow()
        setupBottomNav()
        setupTopBar()

        // Permission gate: if already granted, just start the camera.
        // Otherwise, ask — and the launcher's callback will route to
        // startCamera() or showCameraDeniedMessage() based on the response.
        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // ── Camera permission ──────────────────────────────────────────────────────

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Set up the live camera preview, shutter capture, and frame analysis.
     *
     * Three CameraX use cases are bound to this Activity's lifecycle so they
     * auto-start on resume and auto-stop on pause:
     *   - Preview        → drives the PreviewView (the live viewfinder)
     *   - ImageCapture   → snapshot photos when the shutter is pressed
     *   - ImageAnalysis  → continuous frame stream for YOLO detection
     */
    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.cameraPreview)

        // ProcessCameraProvider is a singleton that lets us bind use cases.
        // getInstance() returns a future because the provider may need to
        // initialize on first call. We wait on the main thread executor so
        // the bind below runs on the UI thread (required for lifecycle binding).
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            // 1) Preview — pipes camera frames to PreviewView.
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 2) ImageCapture — used later by the shutter button.
            imageCapture = ImageCapture.Builder().build()

            // 3) ImageAnalysis — used later for live YOLO detection. KEEP_ONLY_LATEST
            //    drops older frames if the analyzer falls behind, which is what we
            //    want for guidance ("is the paper centered?") rather than recording.
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        // TODO: hand imageProxy to the YOLO detector once it's wired up.
                        //       For now we just close it so CameraX can re-use the buffer.
                        imageProxy.close()
                    }
                }

            try {
                // Unbind any previous use cases (e.g. on a configuration change)
                // before binding fresh ones — CameraX will throw otherwise.
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                // Possible causes: no back camera on the device, another app
                // is holding the camera, or hardware error. Surface the same
                // fallback we use for permission denial.
                Log.e(TAG, "Camera bind failed", e)
                showCameraDeniedMessage()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Graceful fallback when the user denies camera permission.
     * Routes the explanation through the speaking card so the TTS layer
     * (later checklist item) reads it aloud automatically.
     */
    private fun showCameraDeniedMessage() {
        updateSpeakingCard(
            "Camera permission is needed to scan equations. " +
                    "Please enable Camera access for MIJI in Settings."
        )
        setAutoCaptureStatus("CAMERA UNAVAILABLE")
    }

    // ── Top Bar ──────────────────────────────────────────────────────────────

    private fun setupTopBar() {
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            MenuHelper.showClassroomMenu(this)
        }
    }

    // ── Speaking now card ──────────────────────────────────────────────────────
    /**
     * Call [updateSpeakingCard] from your TTS / socket layer to push live
     * guidance text into the UI.
     */
    fun updateSpeakingCard(instruction: String) {
        findViewById<TextView>(R.id.tvSpeakingInstruction).text = instruction
    }

    private fun setupSpeakingCard() {
        // Initial state already set via XML strings.
        // The TTS engine (core/tts) should call updateSpeakingCard() with live text.
    }

    // ── Shutter / Flash / Upload ───────────────────────────────────────────────
    private fun setupShutterRow() {

        // Flash toggle
        findViewById<View>(R.id.btnFlash).setOnClickListener {
            isFlashOn = !isFlashOn
            // TODO: toggle torch via CameraX:
            //   camera.cameraControl.enableTorch(isFlashOn)
            // Optionally tint ivFlash to accent colour when active:
            //   val tint = if (isFlashOn) getColor(R.color.accent) else getColor(R.color.white)
            //   ivFlash.setColorFilter(tint)
        }

        // Shutter — trigger auto-capture or manual capture
        findViewById<FrameLayout>(R.id.btnShutter).setOnClickListener {
            // TODO: call imageCapture.takePicture(...) via CameraX
            //   then pass the ImageProxy to core/detector.py via socket
        }

        // Upload — pick image from gallery
        findViewById<View>(R.id.btnUpload).setOnClickListener {
            // TODO: launch image picker intent
            // val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            // startActivityForResult(pickIntent, REQUEST_CODE_PICK_IMAGE)
        }
    }

    // ── Auto-capture state ─────────────────────────────────────────────────────
    /**
     * Call from your socket / detection layer to reflect current capture state.
     * e.g. "AUTO-CAPTURE READY" / "CAPTURING…" / "PROCESSING…"
     */
    fun setAutoCaptureStatus(status: String) {
        findViewById<TextView>(R.id.tvAutoCaptureLabel).text = status
    }

    // ── Bottom navigation ──────────────────────────────────────────────────────

    private fun setupBottomNav() {
        val nav = findViewById<View>(R.id.bottomNavScan)
        BottomNavHelper.bind(
            navRoot   = nav,
            activeTab = BottomNavHelper.Tab.SCAN,
            onHome    = { startActivity(Intent(this, HomeActivity::class.java)) },
            onScan    = { /* already here */ },
            onProfile = { startActivity(Intent(this, ProfileActivity::class.java)) }
        )
    }

    // ── Lifecycle cleanup ──────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        // Free the analyzer thread. CameraX itself is auto-unbound by the
        // lifecycle binding, so we don't have to do anything for that.
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ScanActivity"
    }
}