package com.example.aicallassist

import java.util.Locale

/**
 * Matches a caller request against contact-specific context.
 */
class ContactContextMatcher {

    /**
     * Determines if a request semantically matches the stored context.
     * In this milestone, we use keyword intersection and category matching.
     */
    fun match(request: String, context: String, category: SensitivityCategory): MatchResult {
        val lowerRequest = request.lowercase(Locale.ROOT)
        val lowerContext = context.lowercase(Locale.ROOT)

        // 1. If context is empty, no match
        if (context.isBlank()) return MatchResult.NO_MATCH

        // 2. Check for category-based alignment
        val categoryKeywords = getKeywordsForCategory(category)
        val hasCategoryInContext = categoryKeywords.any { lowerContext.contains(it) }
        
        // 3. Check for specific subject matching (e.g., delivery, courier)
        val deliveryKeywords = listOf("delivery", "courier", "parcel", "package", "postman")
        val isDeliveryInRequest = deliveryKeywords.any { lowerRequest.contains(it) }
        val isDeliveryInContext = deliveryKeywords.any { lowerContext.contains(it) }

        // Logic for "MATCH"
        if (isDeliveryInRequest && isDeliveryInContext) {
            // Both mention delivery. If it's an OTP request and context mentions code/otp, it's a match.
            if (category == SensitivityCategory.OTP && (lowerContext.contains("code") || lowerContext.contains("otp") || lowerContext.contains("verification"))) {
                return MatchResult.MATCH
            }
            // If it's just a general delivery request and context mentions delivery, it's a match.
            if (category == SensitivityCategory.NONE) return MatchResult.MATCH
        }

        // Logic for "UNCERTAIN"
        if (hasCategoryInContext) {
            // Context mentions the category (e.g., OTP), but maybe not the specific reason (delivery).
            return MatchResult.UNCERTAIN
        }

        // If keywords from request appear in context, maybe it's related
        val requestWords = lowerRequest.split(" ").filter { it.length > 3 }
        val matchCount = requestWords.count { lowerContext.contains(it) }
        
        if (matchCount >= 2) return MatchResult.UNCERTAIN

        return MatchResult.NO_MATCH
    }

    private fun getKeywordsForCategory(category: SensitivityCategory): List<String> {
        return when (category) {
            SensitivityCategory.OTP -> listOf("otp", "code", "verification")
            SensitivityCategory.PASSWORD -> listOf("password", "pin", "passcode")
            SensitivityCategory.FINANCIAL -> listOf("pay", "bank", "transaction", "money")
            SensitivityCategory.PERSONAL -> listOf("address", "private", "personal")
            SensitivityCategory.NONE -> emptyList()
        }
    }
}
