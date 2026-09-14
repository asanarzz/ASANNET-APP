package com.kafinet.asannet

import android.os.LocaleList
import android.text.Spannable
import android.text.SpannableString
import android.text.style.LocaleSpan

/**
 * یه متن فارسی/راست‌به‌چپ رو می‌گیره و برمی‌گردونه، به‌طوری‌که هر عدد انگلیسی توش
 * (مثلاً امتیاز آی‌ام‌دی‌بی یا سال تولید) با همون شکل انگلیسیش نمایش داده بشه،
 * بدون این‌که چینش یا جهت خودِ جمله‌ی فارسی به‌هم بریزه.
 */
fun forceEnglishDigits(text: String): CharSequence {
    if (text.isEmpty()) return text
    val spannable = SpannableString(text)
    val regex = Regex("[0-9]+([.,][0-9]+)?")
    for (match in regex.findAll(text)) {
        spannable.setSpan(
            LocaleSpan(LocaleList.forLanguageTags("en")),
            match.range.first,
            match.range.last + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return spannable
}
