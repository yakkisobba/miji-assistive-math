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
        setupTopBar()
    }

    // ── Top Bar ──────────────────────────────────────────────────────────────

    private fun setupTopBar() {
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            MenuHelper.showClassroomMenu(this)
        }
    }

    // ── Module list ────────────────────────────────────────────────────────────

    private val modules = listOf(
        ModuleConfig(R.id.moduleAddition,       R.string.module_addition,       R.drawable.ic_plus,     LearnModuleActivity.MODULE_ADDITION),
        ModuleConfig(R.id.moduleSubtraction,    R.string.module_subtraction,    R.drawable.ic_minus,    LearnModuleActivity.MODULE_SUBTRACTION),
        ModuleConfig(R.id.moduleMultiplication, R.string.module_multiplication, R.drawable.ic_multiply, LearnModuleActivity.MODULE_MULTIPLICATION),
        ModuleConfig(R.id.moduleDivision,       R.string.module_division,       R.drawable.ic_divide,   LearnModuleActivity.MODULE_DIVISION),
        ModuleConfig(R.id.moduleMixed,          R.string.module_mixed,          R.drawable.ic_mixed,    null)
    )

    private data class ModuleConfig(
        val viewId: Int,
        val labelRes: Int,
        val iconRes: Int,
        val moduleType: String?   // null = not yet implemented (Mixed)
    )

    private fun setupModules() {
        for (module in modules) {
            val row = findViewById<View>(module.viewId)
            row.findViewById<ImageView>(R.id.ivModuleIcon).setImageResource(module.iconRes)
            row.findViewById<TextView>(R.id.tvModuleName).setText(module.labelRes)
            row.setOnClickListener {
                val type = module.moduleType ?: return@setOnClickListener
                val intent = Intent(this, LearnModuleActivity::class.java)
                intent.putExtra(LearnModuleActivity.EXTRA_MODULE_TYPE, type)
                startActivity(intent)
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