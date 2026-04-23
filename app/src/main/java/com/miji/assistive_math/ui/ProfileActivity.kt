package com.miji.assistive_math.ui


import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.miji.assistive_math.R
import com.miji.assistive_math.ui.HomeActivity
import com.miji.assistive_math.ui.ScanActivity
import com.miji.assistive_math.ui.BottomNavHelper
import com.miji.assistive_math.ui.BottomNavHelper.Tab

/**
 * PROFILE SCREEN
 *
 * Shows:
 *  - MIJI logo + hamburger menu
 *  - Profile card  (avatar · name · @username)
 *  - Settings card (Setting 1 toggle · Setting 2 row)
 *  - Logout button  (accent yellow)
 *  - Delete Account button (red / danger)
 *  - Shared bottom navigation bar
 */

class ProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        populateUserInfo()
        setupSettings()
        setupButtons()
        setupBottomNav()
    }

    // ── User info ──────────────────────────────────────────────────────────────

    private fun populateUserInfo() {
        // TODO: replace with real user data from your auth / session layer
        findViewById<TextView>(R.id.tvUserName).text   = "Your Name"
        findViewById<TextView>(R.id.tvUserHandle).text = "@username"

        // TODO: load real avatar with Glide or Coil:
        //   Glide.with(this)
        //       .load(userPhotoUrl)
        //       .circleCrop()
        //       .placeholder(R.drawable.ic_placeholder_person)
        //       .into(findViewById(R.id.ivAvatar))
    }

    // ── Settings ───────────────────────────────────────────────────────────────

    private fun setupSettings() {

        // Setting 1 — toggle
        val toggle1 = findViewById<SwitchCompat>(R.id.toggleSetting1)
        toggle1.setOnCheckedChangeListener { _, isChecked ->
            // TODO: persist preference and apply setting
            // e.g. SharedPreferences / DataStore
        }

        // Setting 2 — tappable row (add your own behaviour)
        findViewById<View>(R.id.rowSetting2).setOnClickListener {
            // TODO: open Setting 2 detail / sub-screen
        }
    }

    // ── Action buttons ─────────────────────────────────────────────────────────

    private fun setupButtons() {

        // Logout
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            showLogoutConfirmation()
        }

        // Delete account
        findViewById<Button>(R.id.btnDeleteAccount).setOnClickListener {
            showDeleteConfirmation()
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Log out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log out") { _, _ ->
                // TODO: clear session, navigate to LoginActivity
                // authViewModel.logout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Delete account")
            .setMessage("This action is permanent and cannot be undone. Are you sure?")
            .setPositiveButton("Delete") { _, _ ->
                // TODO: call delete account API, then clear session
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Bottom navigation ──────────────────────────────────────────────────────

    private fun setupBottomNav() {
        val nav = findViewById<View>(R.id.bottomNavProfile)
        BottomNavHelper.bind(
            navRoot   = nav,
            activeTab = BottomNavHelper.Tab.PROFILE,
            onHome    = { startActivity(Intent(this, HomeActivity::class.java)) },
            onScan    = { startActivity(Intent(this, ScanActivity::class.java)) },
            onProfile = { /* already here */ }
        )
    }
}