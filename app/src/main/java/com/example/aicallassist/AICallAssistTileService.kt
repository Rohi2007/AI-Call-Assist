package com.example.aicallassist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings Tile Service for AI Call Assist.
 * The tile acts as a gateway:
 * - If OFF: Opens the activation UI.
 * - If ON: Deactivates immediately.
 */
class AICallAssistTileService : TileService() {

    override fun onClick() {
        // Do NOT call super.onClick() to avoid any default system behavior
        
        // Requirement: "OFF tile -> opens configuration interface."
        // Requirement: "ACTIVE tile -> opens active-status interface."
        // The tile acts as an ENTRY POINT to the interface, not as an immediate toggle.
        
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val manager = CallAssistManager(this)
        
        val isActive = manager.isActive()
        val endTime = manager.getEndTime()
        
        // Handle auto-expiration sync
        if (isActive && endTime > 0 && System.currentTimeMillis() > endTime) {
            manager.deactivate()
            updateTileState(tile, false)
        } else {
            if (isActive) {
                manager.updateNotification()
            }
            updateTileState(tile, isActive)
        }
    }

    private fun updateTileState(tile: Tile, isActive: Boolean) {
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isActive) "On" else "Off"
        }
        tile.updateTile()
    }
}
