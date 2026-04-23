package com.miji.assistive_math.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.miji.assistive_math.R

object BottomNavHelper {

    enum class Tab { HOME, SCAN, PROFILE }

    fun bind(
        navRoot  : View,
        activeTab: Tab,
        onHome   : () -> Unit,
        onScan   : () -> Unit,
        onProfile: () -> Unit
    ) {
        val tabHome    = navRoot.findViewById<View>(R.id.navHome)
        val tabScan    = navRoot.findViewById<View>(R.id.navScan)
        val tabProfile = navRoot.findViewById<View>(R.id.navProfile)

        val ivHome    = navRoot.findViewById<ImageView>(R.id.ivNavHome)
        val ivScan    = navRoot.findViewById<ImageView>(R.id.ivNavScan)
        val ivProfile = navRoot.findViewById<ImageView>(R.id.ivNavProfile)

        val tvHome    = navRoot.findViewById<TextView>(R.id.tvNavHome)
        val tvScan    = navRoot.findViewById<TextView>(R.id.tvNavScan)
        val tvProfile = navRoot.findViewById<TextView>(R.id.tvNavProfile)

        val bgHome    = navRoot.findViewById<View>(R.id.navHomeBg)
        val bgScan    = navRoot.findViewById<View>(R.id.navScanBg)
        val bgProfile = navRoot.findViewById<View>(R.id.navProfileBg)

        val ctx = navRoot.context
        val accentColor = ContextCompat.getColor(ctx, R.color.accent)
        val whiteColor  = ContextCompat.getColor(ctx, R.color.white)
        val activeBg    = ContextCompat.getDrawable(ctx, R.drawable.bg_nav_active)
        val clearBg     = ContextCompat.getDrawable(ctx, android.R.color.transparent)

        // Reset all to inactive
        listOf(ivHome, ivScan, ivProfile).forEach { it.setColorFilter(whiteColor) }
        listOf(tvHome, tvScan, tvProfile).forEach { it.setTextColor(whiteColor) }
        listOf(bgHome, bgScan, bgProfile).forEach { it.background = clearBg }

        // Set active
        when (activeTab) {
            Tab.HOME    -> { ivHome.setColorFilter(accentColor);    tvHome.setTextColor(accentColor);    bgHome.background = activeBg }
            Tab.SCAN    -> { ivScan.setColorFilter(accentColor);    tvScan.setTextColor(accentColor);    bgScan.background = activeBg }
            Tab.PROFILE -> { ivProfile.setColorFilter(accentColor); tvProfile.setTextColor(accentColor); bgProfile.background = activeBg }
        }

        tabHome.setOnClickListener    { onHome() }
        tabScan.setOnClickListener    { onScan() }
        tabProfile.setOnClickListener { onProfile() }
    }
}