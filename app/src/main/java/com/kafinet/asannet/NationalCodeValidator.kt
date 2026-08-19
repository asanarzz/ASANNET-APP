package com.kafinet.asannet

object NationalCodeValidator {

    /** بررسی صحت کد ملی ایرانی با الگوریتم رقم کنترلی استاندارد. */
    fun isValid(code: String): Boolean {
        if (!code.matches(Regex("^\\d{10}$"))) return false
        if (code.toSet().size == 1) return false // مثل 1111111111 نامعتبر است

        val digits = code.map { it - '0' }
        val check = digits[9]
        var sum = 0
        for (i in 0..8) sum += digits[i] * (10 - i)
        val remainder = sum % 11
        return if (remainder < 2) check == remainder else check == 11 - remainder
    }
}
