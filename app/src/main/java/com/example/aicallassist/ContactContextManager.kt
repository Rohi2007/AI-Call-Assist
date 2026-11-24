package com.example.aicallassist

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages AI-specific context for individual contacts.
 */
class ContactContextManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "ai_contact_context_prefs"
        private const val PREFIX = "ctx_"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveContext(contact: ContactContext) {
        val key = sanitizePhoneNumber(contact.phoneNumber)
        prefs.edit().apply {
            putString("${PREFIX}${key}_name", contact.displayName)
            putString("${PREFIX}${key}_ctx", contact.specialContext)
            putString("${PREFIX}${key}_id", contact.contactId)
            putLong("${PREFIX}${key}_updated", System.currentTimeMillis())
            apply()
        }
    }

    fun getContext(phoneNumber: String): ContactContext? {
        val key = sanitizePhoneNumber(phoneNumber)
        
        // Try exact match first
        var ctx = prefs.getString("${PREFIX}${key}_ctx", null)
        var finalKey = key
        
        if (ctx == null && key.length >= 10) {
            // Try matching last 10 digits for robustness against country codes
            val last10 = key.takeLast(10)
            val allKeys = prefs.all.keys
            val match = allKeys.find { it.startsWith(PREFIX) && it.endsWith("_ctx") && it.removePrefix(PREFIX).removeSuffix("_ctx").takeLast(10) == last10 }
            if (match != null) {
                finalKey = match.removePrefix(PREFIX).removeSuffix("_ctx")
                ctx = prefs.getString("${PREFIX}${finalKey}_ctx", null)
            }
        }

        if (ctx == null) return null
        
        val name = prefs.getString("${PREFIX}${finalKey}_name", "Unknown") ?: "Unknown"
        val id = prefs.getString("${PREFIX}${finalKey}_id", null)
        val updated = prefs.getLong("${PREFIX}${finalKey}_updated", 0L)

        return ContactContext(
            contactId = id,
            phoneNumber = phoneNumber, // Return the requested number
            displayName = name,
            specialContext = ctx,
            updatedAt = updated
        )
    }

    fun deleteContext(phoneNumber: String) {
        val key = sanitizePhoneNumber(phoneNumber)
        prefs.edit().apply {
            remove("${PREFIX}${key}_name")
            remove("${PREFIX}${key}_ctx")
            remove("${PREFIX}${key}_id")
            remove("${PREFIX}${key}_updated")
            apply()
        }
    }
    
    fun getAllContexts(): List<ContactContext> {
        val allKeys = prefs.all.keys
        val phoneNumbers = allKeys
            .filter { it.startsWith(PREFIX) && it.endsWith("_ctx") }
            .map { it.removePrefix(PREFIX).removeSuffix("_ctx") }
        
        // This is a bit inefficient but fine for a prototype with few contexts
        return phoneNumbers.mapNotNull { getContext(it) }
    }

    private fun sanitizePhoneNumber(number: String): String {
        return number.replace(Regex("[^0-9]"), "")
    }
}
