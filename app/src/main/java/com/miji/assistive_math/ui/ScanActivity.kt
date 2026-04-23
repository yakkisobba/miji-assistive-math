package com.miji.assistive_math.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.miji.assistive_math.R
import com.miji.assistive_math.ui.HomeActivity
import com.miji.assistive_math.ui.ProfileActivity
import com.miji.assistive_math.ui.BottomNavHelper
import com.miji.assistive_math.ui.BottomNavHelper.Tab

/**
 * SCAN SCREEN
 */

class ScanActivity : AppCompatActivity() {

    private var isFlashOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        setupSpeakingCard()
        setupShutterRow()
        setupBottomNav()
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
}