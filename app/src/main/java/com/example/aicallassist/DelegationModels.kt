package com.example.aicallassist

/**
 * Represents action categories for delegation.
 */
enum class ActionCategory(val displayName: String) {
    DELIVERY("Delivery & Courier"),
    MESSAGES("Taking Messages"),
    SCHEDULING("Appointments & Scheduling"),
    SECURITY("Security & Authentication (OTPs/Passwords)"),
    FINANCIAL("Payments & Financial Transactions"),
    PERSONAL_APPROVAL("Personal Approval & Authorization"),
    UNKNOWN("Unknown/General")
}

/**
 * Data model for contact-specific AI context.
 */
data class ContactContext(
    val contactId: String? = null,
    val phoneNumber: String,
    val displayName: String,
    val specialContext: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Sensitivity categories for information protection.
 */
enum class SensitivityCategory {
    OTP,
    PASSWORD,
    FINANCIAL,
    PERSONAL,
    NONE
}

/**
 * Match result for context evaluation.
 */
enum class MatchResult {
    MATCH,
    NO_MATCH,
    UNCERTAIN
}

/**
 * Priority levels for calls.
 */
enum class Priority {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Result of a decision evaluation.
 */
enum class Decision {
    ALLOW,
    BLOCK,
    NEEDS_USER
}

/**
 * Model representing the outcome of the Decision Engine.
 */
data class DecisionResult(
    val decision: Decision,
    val reason: String,
    val matchedCategory: ActionCategory,
    val priority: Priority = Priority.LOW,
    val contextMatch: MatchResult = MatchResult.NO_MATCH
)

/**
 * Model for call interaction summary ("While You Were Away").
 */
data class CallInteractionSummary(
    val callerName: String,
    val callerPhoneNumber: String,
    val identifiedContact: Boolean,
    val reason: String,
    val priority: Priority,
    val decision: Decision,
    val actionTaken: String,
    val sensitiveRequestDetected: Boolean,
    val blockedSensitiveRequest: Boolean,
    val requiresUserAttention: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Data model for the temporary delegation session state (legacy support if needed, but primarily session based).
 */
data class DelegationPolicy(
    val userName: String = "User",
    val sessionDurationMillis: Long = 0L,
    val activeSession: Boolean = false,
    val expiresAt: Long = 0L
)
