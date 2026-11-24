package com.example.aicallassist

import android.R
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class CallAssistManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "ai_call_assist_prefs"
        private const val KEY_IS_ACTIVE = "is_active"
        private const val KEY_END_TIME = "end_time"
        private const val KEY_SELECTED_DURATION = "selected_duration"
        private const val KEY_SUMMARIES = "call_summaries"
        private const val KEY_LAST_CALLER = "last_caller"
        private const val KEY_LAST_CALL_ACTIVE = "last_call_active"
        private const val KEY_LAST_PROCESSED_TIME = "last_processed_time"
        
        private const val KEY_ACTIVE_CONTACT_NAME = "active_contact_name"
        private const val KEY_ACTIVE_CONTACT_PHONE = "active_contact_phone"
        private const val KEY_ACTIVE_CONTACT_CONTEXT = "active_contact_ctx"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ai_call_assist_channel"
        private const val CHANNEL_NAME = "AI Call Assist"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val gson = Gson()

    fun isActive(): Boolean = prefs.getBoolean(KEY_IS_ACTIVE, false)

    fun getEndTime(): Long = prefs.getLong(KEY_END_TIME, 0L)

    fun getSelectedDuration(): Long = prefs.getLong(KEY_SELECTED_DURATION, 0L)

    fun getActiveContactInfo(): Triple<String, String, String>? {
        val name = prefs.getString(KEY_ACTIVE_CONTACT_NAME, null) ?: return null
        val phone = prefs.getString(KEY_ACTIVE_CONTACT_PHONE, "") ?: ""
        val ctx = prefs.getString(KEY_ACTIVE_CONTACT_CONTEXT, "") ?: ""
        return Triple(name, phone, ctx)
    }

    fun activate(durationMillis: Long, contactName: String, contactPhone: String, contactCtx: String) {
        val endTime = System.currentTimeMillis() + durationMillis
        
        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, true)
            .putLong(KEY_END_TIME, endTime)
            .putLong(KEY_SELECTED_DURATION, durationMillis)
            .putString(KEY_ACTIVE_CONTACT_NAME, contactName)
            .putString(KEY_ACTIVE_CONTACT_PHONE, contactPhone)
            .putString(KEY_ACTIVE_CONTACT_CONTEXT, contactCtx)
            .commit()

        createNotificationChannel()
        showNotification(durationMillis)
        scheduleExpiry(endTime)
        updateTile()
    }

    fun deactivate() {
        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, false)
            .putLong(KEY_END_TIME, 0L)
            .remove(KEY_ACTIVE_CONTACT_NAME)
            .remove(KEY_ACTIVE_CONTACT_PHONE)
            .remove(KEY_ACTIVE_CONTACT_CONTEXT)
            .commit()

        cancelExpiry()
        removeNotification()
        updateTile()
    }

    fun handleIncomingCall(phoneNumber: String?, source: String) {
        val now = System.currentTimeMillis()
        val lastProcessed = prefs.getLong(KEY_LAST_PROCESSED_TIME, 0L)
        if (now - lastProcessed < 2000) return
        
        val isActive = isActive()
        val activeContact = getActiveContactInfo()
        
        var callerName = "Unknown Caller"
        var contactFound = false
        var contactContext: String? = null
        
        if (phoneNumber != null) {
            val realName = ContactLookupHelper.lookupContactName(context, phoneNumber)
            if (realName != null) {
                callerName = realName
                contactFound = true
            }
            
            if (isActive && activeContact != null) {
                val normalizedIncoming = sanitizePhoneNumber(phoneNumber)
                val normalizedActive = sanitizePhoneNumber(activeContact.second)
                if (normalizedIncoming.takeLast(10) == normalizedActive.takeLast(10)) {
                    contactContext = activeContact.third
                }
            }
        }
        
        prefs.edit()
            .putString(KEY_LAST_CALLER, "${phoneNumber ?: "Unknown"} ($source)")
            .putBoolean(KEY_LAST_CALL_ACTIVE, isActive)
            .putLong(KEY_LAST_PROCESSED_TIME, now)
            .commit()
            
        val summary = CallInteractionSummary(
            callerName = callerName,
            callerPhoneNumber = phoneNumber ?: "Unknown",
            identifiedContact = contactFound,
            reason = "Incoming call detected ($source)",
            priority = Priority.MEDIUM,
            decision = Decision.NEEDS_USER,
            actionTaken = if (isActive) "Processing incoming call info..." else "Ignored (AI OFF)",
            sensitiveRequestDetected = false,
            blockedSensitiveRequest = false,
            requiresUserAttention = !isActive,
            timestamp = System.currentTimeMillis()
        )
        addSummary(summary)
        
        if (isActive) {
            showDetectionNotification(callerName, phoneNumber, contactFound, contactContext)
        }
    }

    private fun sanitizePhoneNumber(number: String): String {
        return number.replace(Regex("[^0-9]"), "")
    }

    fun getLastCallerInfo(): Pair<String, Boolean> {
        val number = prefs.getString(KEY_LAST_CALLER, "None") ?: "None"
        val active = prefs.getBoolean(KEY_LAST_CALL_ACTIVE, false)
        return Pair(number, active)
    }

    private fun showDetectionNotification(name: String, number: String?, found: Boolean, ctx: String?) {
        val info = "Caller: $name\nContact: ${if (found) "Found" else "Not Found"}\nAI Context: ${ctx ?: "None"}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Incoming Call Detected")
            .setContentText("AI Assist processing: $name")
            .setStyle(NotificationCompat.BigTextStyle().bigText(info))
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(2001, notification)
    }

    fun addSummary(summary: CallInteractionSummary) {
        val summaries = getSummaries().toMutableList()
        summaries.add(0, summary)
        prefs.edit().putString(KEY_SUMMARIES, gson.toJson(summaries)).commit()
    }

    fun getSummaries(): List<CallInteractionSummary> {
        val json = prefs.getString(KEY_SUMMARIES, null) ?: return emptyList()
        val type = object : TypeToken<List<CallInteractionSummary>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun clearSummaries() {
        prefs.edit().remove(KEY_SUMMARIES).commit()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows status of AI Call Assist" }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateNotification() {
        if (isActive()) {
            val remaining = getEndTime() - System.currentTimeMillis()
            if (remaining > 0) showNotification(remaining)
        }
    }

    private fun showNotification(remainingMillis: Long) {
        val minutes = remainingMillis / 60000
        val hours = minutes / 60
        val remainingText = if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("AI Call Assist Active")
            .setContentText("Handling calls on your behalf • $remainingText remaining")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun removeNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun scheduleExpiry(triggerAtMillis: Long) {
        val intent = Intent(context, CallAssistExpiryReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    private fun cancelExpiry() {
        val intent = Intent(context, CallAssistExpiryReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
    }

    fun updateTile() {
        TileService.requestListeningState(context, ComponentName(context, AICallAssistTileService::class.java))
    }
}
