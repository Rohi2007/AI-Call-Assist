package com.example.aicallassist

import java.util.Locale

/**
 * Context-aware Decision Engine for AI Call Assist.
 */
class DecisionEngine {

    private val sensitivityDetector = SensitivityDetector()
    private val contextMatcher = ContactContextMatcher()
    private val priorityEngine = PriorityEngine()

    fun evaluateRequest(
        callerName: String,
        phoneNumber: String,
        contactContext: String?,
        request: String
    ): DecisionResult {
        val lowerRequest = request.lowercase(Locale.ROOT)
        val sensitivity = sensitivityDetector.detect(lowerRequest)
        val priority = priorityEngine.determinePriority(lowerRequest)
        
        // 1. If the request is not sensitive, we generally allow recording it as a message
        if (sensitivity == SensitivityCategory.NONE) {
            val matchedCategory = detectCategory(lowerRequest)
            
            if (contactContext != null) {
                val matchResult = contextMatcher.match(lowerRequest, contactContext, sensitivity)
                return if (matchResult == MatchResult.MATCH) {
                    DecisionResult(
                        Decision.ALLOW,
                        "Request matches contact-specific context.",
                        matchedCategory,
                        priority,
                        matchResult
                    )
                } else {
                    DecisionResult(
                        Decision.ALLOW,
                        "Non-sensitive request recorded as a message.",
                        matchedCategory,
                        priority,
                        matchResult
                    )
                }
            } else {
                // UNKNOWN CALLER: Only ALLOW if it's clearly a "call back" message
                val safeKeywords = listOf("later", "tomorrow", "message", "call back")
                return if (matchedCategory == ActionCategory.MESSAGES && safeKeywords.any { lowerRequest.contains(it) }) {
                    DecisionResult(
                        Decision.ALLOW,
                        "Simple message recorded.",
                        matchedCategory,
                        Priority.LOW,
                        MatchResult.NO_MATCH
                    )
                } else {
                    DecisionResult(
                        Decision.NEEDS_USER,
                        "Unknown request requires your personal attention.",
                        matchedCategory,
                        priority,
                        MatchResult.NO_MATCH
                    )
                }
            }
        }

        // 2. Sensitive Request handling
        if (contactContext != null) {
            val matchResult = contextMatcher.match(lowerRequest, contactContext, sensitivity)
            
            return when (matchResult) {
                MatchResult.MATCH -> DecisionResult(
                    Decision.ALLOW,
                    "Sensitive request matches contact-specific authorization.",
                    detectCategory(lowerRequest),
                    priority,
                    MatchResult.MATCH
                )
                MatchResult.UNCERTAIN -> DecisionResult(
                    Decision.NEEDS_USER,
                    "Sensitive request partially matches context. Requires user approval.",
                    detectCategory(lowerRequest),
                    Priority.HIGH,
                    MatchResult.UNCERTAIN
                )
                MatchResult.NO_MATCH -> DecisionResult(
                    Decision.BLOCK,
                    "Sensitive request does not match contact-specific authorization.",
                    detectCategory(lowerRequest),
                    Priority.HIGH,
                    MatchResult.NO_MATCH
                )
            }
        }

        // 3. Sensitive request with no context
        return DecisionResult(
            Decision.BLOCK,
            "Sensitive information request from unauthorized caller blocked.",
            detectCategory(lowerRequest),
            Priority.HIGH,
            MatchResult.NO_MATCH
        )
    }

    private fun detectCategory(request: String): ActionCategory {
        return when {
            request.contains("delivery") || request.contains("courier") || request.contains("parcel") -> ActionCategory.DELIVERY
            request.contains("otp") || request.contains("code") || request.contains("password") -> ActionCategory.SECURITY
            request.contains("pay") || request.contains("bank") || request.contains("transaction") -> ActionCategory.FINANCIAL
            request.contains("appointment") || request.contains("schedule") || request.contains("meeting") -> ActionCategory.SCHEDULING
            request.contains("approve") || request.contains("authorize") || request.contains("confirm") -> ActionCategory.PERSONAL_APPROVAL
            else -> ActionCategory.MESSAGES
        }
    }
}
