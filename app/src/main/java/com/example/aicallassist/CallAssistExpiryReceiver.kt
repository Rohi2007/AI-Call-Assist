package com.example.aicallassist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CallAssistExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = CallAssistManager(context)
        if (manager.isActive()) {
            manager.deactivate()
            Toast.makeText(context, "AI Call Assist expired", Toast.LENGTH_SHORT).show()
        }
    }
}
