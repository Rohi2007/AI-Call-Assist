package com.example.aicallassist

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi

/**
 * Modern Android Service for screening and identifying real incoming calls.
 */
@RequiresApi(Build.VERSION_CODES.N)
class AICallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val handle = callDetails.handle
        val phoneNumber = handle?.schemeSpecificPart
        
        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        } else {
            // Before Q, onScreenCall was primarily for incoming calls
            true
        }

        if (isIncoming) {
            val manager = CallAssistManager(this)
            manager.handleIncomingCall(phoneNumber, "CallScreening")
            
            val response = CallResponse.Builder()
            
            // Note: We no longer automatically silence calls here.
            // The user is responsible for setting their phone to Silent/DND.
            
            respondToCall(callDetails, response.build())
        } else {
            respondToCall(callDetails, CallResponse.Builder().build())
        }
    }
}
