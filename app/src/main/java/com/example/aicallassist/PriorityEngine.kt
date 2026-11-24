package com.example.aicallassist

import java.util.Locale

/**
 * Determines the priority of a call based on the request content.
 */
class PriorityEngine {

    private val highPriorityKeywords = listOf("hospital", "emergency", "urgent", "immediate", "doctor", "police", "security", "threat")
    private val mediumPriorityKeywords = listOf("delivery", "appointment", "reservation", "confirm", "meeting", "schedule", "work", "office")
    private val lowPriorityKeywords = listOf("call back", "later", "free", "chat", "hi", "hello", "friend")

    fun determinePriority(request: String): Priority {
        val lower = request.lowercase(Locale.ROOT)

        return when {
            containsAny(lower, highPriorityKeywords) -> Priority.HIGH
            containsAny(lower, mediumPriorityKeywords) -> Priority.MEDIUM
            containsAny(lower, lowPriorityKeywords) -> Priority.LOW
            else -> Priority.MEDIUM // Default for unknown reasons
        }
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }
}
