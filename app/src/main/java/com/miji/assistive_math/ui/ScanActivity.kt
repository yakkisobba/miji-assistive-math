package com.miji.assistive_math.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
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
import androidx.exifinterface.media.ExifInterface
import com.miji.assistive_math.R
import com.miji.assistive_math.ml.ExpressionRecognizer
import com.miji.assistive_math.ml.RecognitionOutput
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SCAN SCREEN
 */
class ScanActivity : AppCompatActivity(){
    private var isFlashOn = false
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var isCapturing = false
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var expressionRecognizer: ExpressionRecognizer? = null
    private lateinit var tts: TextToSpeech

    // Throttle guidance so TTS doesn't spam every frame
    private var lastGuidanceTime = 0L
    private var lastDirection = ""

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

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.ENGLISH
            }
        }

        setupSpeakingCard()
        setupShutterRow()
        setupBottomNav()
        setupTopBar()

        if (hasCameraPermission()) startCamera()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        cameraExecutor.shutdown()
    }

    // ── ExpressionRecognizer ───────────────────────────────────────────────────

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

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!isCapturing) {
                            val bitmap = imageProxy.toBitmap()
                            val direction = analyzeFrame(bitmap)
                            updateGuidance(direction)
                        }
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
        speakText("Camera permission is needed to scan equations.")
    }

    // ── TTS helper ─────────────────────────────────────────────────────────────

    private fun speakText(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // ── Guidance System ────────────────────────────────────────────────────────

    data class GuidanceCue(
        val direction: String,
        val status: String,
        val isCentered: Boolean = false
    )

    fun updateGuidance(direction: String) {
        val cue = when (direction) {
            "move_left"       -> GuidanceCue("Move camera to the left",           "ADJUST")
            "move_right"      -> GuidanceCue("Move camera to the right",          "ADJUST")
            "move_up"         -> GuidanceCue("Move camera up",                    "ADJUST")
            "move_down"       -> GuidanceCue("Move camera down",                  "ADJUST")
            "move_up_left"    -> GuidanceCue("Move camera up and to the left",    "ADJUST")
            "move_up_right"   -> GuidanceCue("Move camera up and to the right",   "ADJUST")
            "move_down_left"  -> GuidanceCue("Move camera down and to the left",  "ADJUST")
            "move_down_right" -> GuidanceCue("Move camera down and to the right", "ADJUST")
            "hold_still"      -> GuidanceCue("Hold still. Capturing equation…",   "CAPTURING", true)
            "capturing"       -> GuidanceCue("Capturing equation…",               "CAPTURING…")
            else              -> GuidanceCue("Point camera at an equation",        "SEARCHING…")
        }
        runOnUiThread { applyGuidance(cue) }
    }

    private fun applyGuidance(cue: GuidanceCue) {
        updateSpeakingCard(cue.direction)
        setAutoCaptureStatus(cue.status)

        // Only speak if direction changed or 3 seconds have passed
        // This prevents TTS from spamming every frame
        val now = System.currentTimeMillis()
        if (cue.direction != lastDirection || now - lastGuidanceTime > GUIDANCE_INTERVAL_MS) {
            speakText(cue.direction)
            lastDirection = cue.direction
            lastGuidanceTime = now
        }

        if (cue.isCentered && !isCapturing) {
            capturePhoto()
        }
    }

    // ── Frame Analysis ─────────────────────────────────────────────────────────

    /**
     * Analyzes the camera frame to determine where the equation is
     * and returns a direction string for updateGuidance()
     */
    private fun analyzeFrame(bitmap: Bitmap): String {
        val cols = 3
        val rows = 3
        val cellW = bitmap.width / cols
        val cellH = bitmap.height / rows

        val brightness = Array(rows) { row ->
            FloatArray(cols) { col ->
                averageBrightness(bitmap, col * cellW, row * cellH, cellW, cellH)
            }
        }

        var maxContrast = -1f
        var contentRow = 1
        var contentCol = 1

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val contrast = computeLocalContrast(brightness, r, c, rows, cols)
                if (contrast > maxContrast) {
                    maxContrast = contrast
                    contentRow = r
                    contentCol = c
                }
            }
        }

        if (maxContrast < CONTRAST_THRESHOLD) {
            return "searching"
        }

        val dRow = contentRow - 1
        val dCol = contentCol - 1

        return when {
            dRow == 0 && dCol == 0 -> "hold_still"
            dRow < 0 && dCol < 0   -> "move_up_left"
            dRow < 0 && dCol > 0   -> "move_up_right"
            dRow > 0 && dCol < 0   -> "move_down_left"
            dRow > 0 && dCol > 0   -> "move_down_right"
            dRow < 0               -> "move_up"
            dRow > 0               -> "move_down"
            dCol < 0               -> "move_left"
            dCol > 0               -> "move_right"
            else                   -> "hold_still"
        }
    }

    private fun averageBrightness(
        bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int
    ): Float {
        var total = 0L
        var count = 0
        var px = x
        while (px < x + w && px < bitmap.width) {
            var py = y
            while (py < y + h && py < bitmap.height) {
                val pixel = bitmap.getPixel(px, py)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8)  and 0xFF
                val b = pixel          and 0xFF
                total += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
                count++
                py += SAMPLE_STEP
            }
            px += SAMPLE_STEP
        }
        return if (count == 0) 0f else total.toFloat() / count
    }

    private fun computeLocalContrast(
        brightness: Array<FloatArray>, row: Int, col: Int, rows: Int, cols: Int
    ): Float {
        val cellVal = brightness[row][col]
        var neighbourSum = 0f
        var neighbourCount = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = row + dr
                val nc = col + dc
                if (nr in 0 until rows && nc in 0 until cols) {
                    neighbourSum += brightness[nr][nc]
                    neighbourCount++
                }
            }
        }
        val avg = if (neighbourCount == 0) cellVal else neighbourSum / neighbourCount
        return Math.abs(cellVal - avg)
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

        findViewById<View>(R.id.btnFlash).setOnClickListener {
            isFlashOn = !isFlashOn
            camera?.cameraControl?.enableTorch(isFlashOn)
            val tint = if (isFlashOn) getColor(R.color.accent) else getColor(R.color.white)
            ivFlash.setColorFilter(tint)
        }

        findViewById<FrameLayout>(R.id.btnShutter).setOnClickListener {
            capturePhoto()
        }

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
        speakText("Hold still. Capturing equation.")

        val photoFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = Uri.fromFile(photoFile)
                    setAutoCaptureStatus("PROCESSING…")
                    updateSpeakingCard("Processing equation…")
                    speakText("Processing equation.")
                    processImageUri(uri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed: ${exception.message}", exception)
                    isCapturing = false
                    setAutoCaptureStatus("AUTO-CAPTURE READY")
                    updateSpeakingCard("Capture failed. Please try again.")
                    speakText("Capture failed. Please try again.")
                }
            }
        )
    }

    // ── Gallery result ─────────────────────────────────────────────────────────

    private fun handleGalleryImage(uri: Uri) {
        Log.d(TAG, "Gallery image selected: $uri")
        try {
            // Verify the URI is readable
            contentResolver.getType(uri)?.let { 
                Log.d(TAG, "Image MIME type: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not verify selected image: ${e.message}")
        }
        
        // Reset capture state before processing
        isCapturing = true
        setAutoCaptureStatus("PROCESSING…")
        updateSpeakingCard("Processing selected image…")
        speakText("Processing selected image.")
        processImageUri(uri)
    }

    // ── Auto-capture state ─────────────────────────────────────────────────────

    fun setAutoCaptureStatus(status: String) {
        findViewById<TextView>(R.id.tvAutoCaptureLabel).text = status
    }

    // ── Process Image ──────────────────────────────────────────────────────────

    private fun processImageUri(uri: Uri) {
        cameraExecutor.execute {
            try {
                Log.d(TAG, "Starting image processing for URI: $uri")
                val bitmap = loadBitmapFromUri(uri)

                if (bitmap == null) {
                    Log.e(TAG, "Failed to load bitmap from URI")
                    runOnUiThread {
                        isCapturing = false
                        setAutoCaptureStatus("FAILED")
                        updateSpeakingCard("Could not read the image. Please try again.")
                        speakText("Could not read the image. Please try again.")
                    }
                    return@execute
                }

                Log.d(TAG, "Bitmap loaded successfully: ${bitmap.width}x${bitmap.height}")
                Log.d(TAG, "Starting expression recognition...")
                val output = getExpressionRecognizer().recognizeExpression(bitmap)

                Log.d(TAG, "Recognition completed.")
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
                    val rejectedCount = output.predictions.count { !it.accepted }
                    val majorityRejected = rejectedCount > output.predictions.size / 2

                    if (output.detectedSymbolCount == 0 || output.expression.isBlank()) {
                        isCapturing = false  // ← reset only on failure
                        setAutoCaptureStatus("NO SYMBOLS FOUND")
                        updateSpeakingCard("No equation symbols were detected. Please try again.")
                        speakText("No equation symbols were detected. Please try again.")
                    } else if (majorityRejected) {
                        isCapturing = false  // ← reset only on failure
                        setAutoCaptureStatus("UNCERTAIN")
                        updateSpeakingCard("The equation was unclear. Please retake the photo or move closer.")
                        speakText("The equation was unclear. Please retake the photo or move closer.")
                    } else {
                        setAutoCaptureStatus("DONE")
                        updateSpeakingCard("Equation recognized.")
                        speakText("Equation recognized.")
                        openResultScreen(output)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Processing failed: ${e.message}", e)
                runOnUiThread {
                    isCapturing = false
                    setAutoCaptureStatus("FAILED")
                    updateSpeakingCard("Processing failed: ${e.message}")
                    speakText("Processing failed. Please try again.")
                }
            }
        }
    }

    // ── Bitmap helpers ─────────────────────────────────────────────────────────

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        try {
            // Copy the URI content to a temporary cache file for reliable access
            val tempFile = File(cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
            
            contentResolver.openInputStream(uri).use { inputStream ->
                if (inputStream == null) {
                    Log.e(TAG, "Could not open input stream from URI: $uri")
                    return null
                }
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            // Now decode the bitmap from the temporary file
            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from temporary file")
                tempFile.delete()
                return null
            }
            
            // Rotate if needed and clean up temp file
            val rotatedBitmap = rotateBitmapIfRequired(tempFile, bitmap)
            tempFile.delete()
            
            return rotatedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI: ${e.message}", e)
            return null
        }
    }

    private fun rotateBitmapIfRequired(file: File, bitmap: Bitmap): Bitmap {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotationDegrees == 0f) return bitmap

            val matrix = Matrix()
            matrix.postRotate(rotationDegrees)
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading EXIF data: ${e.message}", e)
            return bitmap // Return unrotated bitmap if EXIF reading fails
        }
    }

    // ── Open Result Screen ─────────────────────────────────────────────────────

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
        if (output.predictions.isEmpty()) return 0f
        return output.predictions.map { it.confidence }.average().toFloat() * 100f
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
            '0' to "zero", '1' to "one", '2' to "two",
            '3' to "three", '4' to "four", '5' to "five",
            '6' to "six", '7' to "seven", '8' to "eight", '9' to "nine"
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

    companion object {
        private const val TAG = "ScanActivity"
        private const val SAMPLE_STEP = 8
        private const val CONTRAST_THRESHOLD = 10f
        private const val GUIDANCE_INTERVAL_MS = 3000L // speak every 3 seconds
    }
}