package com.miji.assistive_math.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import com.miji.assistive_math.R

object MenuHelper {

    fun showClassroomMenu(context: Context) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_classroom_session)

        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
        }

        val btnClose = dialog.findViewById<View>(R.id.btnClose)
        val btnCreate = dialog.findViewById<View>(R.id.btnCreateLobby)
        val btnJoin = dialog.findViewById<View>(R.id.btnJoinLobby)
        val btnHow = dialog.findViewById<View>(R.id.btnHowItWorks)

        btnClose.setOnClickListener { dialog.dismiss() }
        
        btnCreate.setOnClickListener {
            // TODO: Implement Create Lobby action
            dialog.dismiss()
        }
        
        btnJoin.setOnClickListener {
            // TODO: Implement Join Lobby action
            dialog.dismiss()
        }
        
        btnHow.setOnClickListener {
            // TODO: Implement How It Works action
            dialog.dismiss()
        }

        dialog.show()
    }
}