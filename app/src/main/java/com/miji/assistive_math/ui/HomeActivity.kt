package com.miji.assistive_math.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.miji.assistive_math.R
import com.miji.assistive_math.ui.ProfileActivity
import com.miji.assistive_math.ui.ScanActivity

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        setupModules()
        setupScanCard()
        setupBottomNav()
    }

    // ── Module list ────────────────────────────────────────────────────────────

    private data class ModuleConfig(
        val viewId: Int,
        val labelRes: Int,
        val iconRes: Int
    )

    private val modules = listOf(
        ModuleConfig(
            viewId   = R.id.moduleAddition,
            labelRes = R.string.module_addition,
            iconRes  = R.drawable.ic_plus
        ),
        ModuleConfig(
            viewId   = R.id.moduleSubtraction,
            labelRes = R.string.module_subtraction,
            iconRes  = R.drawable.ic_minus
        ),
        ModuleConfig(
            viewId   = R.id.moduleMultiplication,
            labelRes = R.string.module_multiplication,
            iconRes  = R.drawable.ic_multiply
        ),
        ModuleConfig(
            viewId   = R.id.moduleDivision,
            labelRes = R.string.module_division,
            iconRes  = R.drawable.ic_divide
        ),
        ModuleConfig(
            viewId   = R.id.moduleMixed,
            labelRes = R.string.module_mixed,
            iconRes  = R.drawable.ic_mixed
        )
    )

    private fun setupModules() {
        for (module in modules) {
            val row = findViewById<View>(module.viewId)
            row.findViewById<ImageView>(R.id.ivModuleIcon).setImageResource(module.iconRes)
            row.findViewById<TextView>(R.id.tvModuleName).setText(module.labelRes)
            row.setOnClickListener {
                // TODO: launch ModuleActivity with module type as extra
                // val intent = Intent(this, ModuleActivity::class.java)
                // intent.putExtra("module_type", module.labelRes)
                // startActivity(intent)
            }
        }
    }

    // ── Scan card ─────────────────────────────────────────────────────────────

    private fun setupScanCard() {
        findViewById<View>(R.id.cardScanEquation).setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
    }

    // ── Bottom navigation ──────────────────────────────────────────────────────

    private fun setupBottomNav() {
        val nav = findViewById<View>(R.id.bottomNav)   // root id from layout_bottom_nav
        BottomNavHelper.bind(
            navRoot    = nav,
            activeTab  = BottomNavHelper.Tab.HOME,
            onHome     = { /* already here */ },
            onScan     = { startActivity(Intent(this, ScanActivity::class.java)) },
            onProfile  = { startActivity(Intent(this, ProfileActivity::class.java)) }
        )
    }
}