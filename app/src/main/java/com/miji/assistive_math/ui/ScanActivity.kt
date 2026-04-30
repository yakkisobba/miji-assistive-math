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


import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.miji.assistive_math.ml.ExpressionRecognizer
import com.miji.assistive_math.ml.RecognitionOutput


/**
 * SCAN SCREEN
 */
class ScanActivity : AppCompatActivity() {

    private var isFlashOn = false
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var isCapturing = false
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var expressionRecognizer: ExpressionRecognizer? = null

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

    private fun getExpressionRecognizer(): ExpressionRecognizer {
        if (expressionRecognizer == null) {
            Log.d(TAG, "Initializing ExpressionRecognizer and model...")
            expressionRecognizer = ExpressionRecognizer(applicationContext)
            Log.d(TAG, "ExpressionRecognizer initialized.")
        }

        return expressionRecognizer!!
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

                    processImageUri(uri)
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

        processImageUri(uri)
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

    private fun processImageUri(uri: Uri) {
        cameraExecutor.execute {
            try {
                val bitmap = loadBitmapFromUri(uri)

                if (bitmap == null) {
                    runOnUiThread {
                        setAutoCaptureStatus("FAILED")
                        updateSpeakingCard("Could not read the image. Please try again.")
                    }
                    return@execute
                }

                val recognizer = getExpressionRecognizer()
                val output = recognizer.recognizeExpression(bitmap)

                Log.d(TAG, "Detected symbols: ${output.detectedSymbolCount}")
                Log.d(TAG, "Labels: ${output.labels}")
                Log.d(TAG, "Expression: ${output.expression}")

                output.predictions.forEachIndexed { index, prediction ->
                    Log.d(
                        TAG,
                        "Symbol ${index + 1}: " +
                                "Top1=${prediction.label}, " +
                                "Conf=${prediction.confidence}, " +
                                "Top2=${prediction.secondLabel}, " +
                                "Top2Conf=${prediction.secondConfidence}, " +
                                "Accepted=${prediction.accepted}"
                    )
                }

                runOnUiThread {

                    val hasRejectedPrediction = output.predictions.any {!it.accepted}

                    if (output.detectedSymbolCount == 0 || output.expression.isBlank()) {
                        setAutoCaptureStatus("NO SYMBOLS FOUND")
                        updateSpeakingCard("No equation symbols were detected. Please try again.")
                    } else if (hasRejectedPrediction){
                        setAutoCaptureStatus("UNCERTAIN")
                        updateSpeakingCard("The equation was unclear. Please retake the photo or move closer.")

                        Log.d(TAG, "Recognition rejected because atleast one symbol was not accepted.")
                        Log.d(TAG, "Rejected output labels: ${output.labels}")
                        Log.d(TAG, "Rejected expression: ${output.expression}")
                    } else {
                        setAutoCaptureStatus("DONE")
                        updateSpeakingCard("Equation recognized.")
                        openResultScreen(output)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)

                runOnUiThread {
                    setAutoCaptureStatus("FAILED")
                    updateSpeakingCard("Processing failed. Please try again.")
                }
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        val bitmap = contentResolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) {
                null
            } else {
                BitmapFactory.decodeStream(inputStream)
            }
        }

        if (bitmap == null) {
            return null
        }

        return rotateBitmapIfRequired(uri, bitmap)
    }

    private fun rotateBitmapIfRequired(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = contentResolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) {
                ExifInterface.ORIENTATION_NORMAL
            } else {
                val exif = ExifInterface(inputStream)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (rotationDegrees == 0f) {
            return bitmap
        }

        val matrix = Matrix()
        matrix.postRotate(rotationDegrees)

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private fun openResultScreen(output: RecognitionOutput) {
        val confidencePercent = calculateAverageConfidence(output)
        val displayEquation = formatExpressionForDisplay(output.expression)
        val phonetic = expressionToPhonetic(output.expression)

        val intent = Intent(this, ScanResultActivity::class.java).apply {
            putExtra(ScanResultActivity.EXTRA_EQUATION_DISPLAY, displayEquation)
            putExtra(ScanResultActivity.EXTRA_EQUATION_PHONETIC, phonetic)
            putExtra(ScanResultActivity.EXTRA_CONFIDENCE, confidencePercent)
        }

        startActivity(intent)
    }

    private fun calculateAverageConfidence(output: RecognitionOutput): Float {
        if (output.predictions.isEmpty()) {
            return 0f
        }

        val average = output.predictions.map { it.confidence }.average().toFloat()

        return average * 100f
    }

    private fun formatExpressionForDisplay(expression: String): String {
        return expression
            .replace("*", " × ")
            .replace("/", " ÷ ")
            .replace("+", " + ")
            .replace("-", " - ")
            .trim()
    }

    private fun expressionToPhonetic(expression: String): String {
        val digitWords = mapOf(
            '0' to "zero",
            '1' to "one",
            '2' to "two",
            '3' to "three",
            '4' to "four",
            '5' to "five",
            '6' to "six",
            '7' to "seven",
            '8' to "eight",
            '9' to "nine"
        )

        val words = mutableListOf<String>()

        for (char in expression) {
            val word = when (char) {
                in '0'..'9' -> digitWords[char] ?: char.toString()
                '+' -> "plus"
                '-' -> "minus"
                '*' -> "times"
                '/' -> "divided by"
                '.' -> "point"
                else -> char.toString()
            }

            words.add(word)
        }

        return words.joinToString(" ")
    }

    companion object {
        private const val TAG = "ScanActivity"
    }
}