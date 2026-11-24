package com.example.aicallassist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * BroadcastReceiver for real incoming cellular call detection.
 */
class IncomingCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                // Incoming call is ringing
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                Log.d("IncomingCallReceiver", "Incoming call detected: $incomingNumber")
                
                val manager = CallAssistManager(context)
                manager.handleIncomingCall(incomingNumber, "TelephonyManager")
            }
        }
    }
}
