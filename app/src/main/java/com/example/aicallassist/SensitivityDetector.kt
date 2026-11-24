package com.example.aicallassist

import java.util.Locale

/**
 * Detects sensitive information requests in caller speech.
 */
class SensitivityDetector {

    private val otpKeywords = listOf("otp", "one time password", "verification code", "auth code", "digit code", "6 digit", "4 digit")
    private val passwordKeywords = listOf("password", "pin", "passcode", "secret code", "login")
    private val financialKeywords = listOf("payment", "pay", "bank", "credit card", "debit card", "cvv", "expiry date", "transaction", "money", "transfer")
    private val personalKeywords = listOf("address", "email", "social security", "aadhar", "dob", "birth date", "private", "personal")

    fun detect(request: String): SensitivityCategory {
        val lower = request.lowercase(Locale.ROOT)
        
        return when {
            containsAny(lower, otpKeywords) -> SensitivityCategory.OTP
            containsAny(lower, passwordKeywords) -> SensitivityCategory.PASSWORD
            containsAny(lower, financialKeywords) -> SensitivityCategory.FINANCIAL
            containsAny(lower, personalKeywords) -> SensitivityCategory.PERSONAL
            else -> SensitivityCategory.NONE
        }
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }
}
