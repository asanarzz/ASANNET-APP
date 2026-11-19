package com.kafinet.asannet

private val SEASON_SXXEXX = Regex("(?:^|[^A-Za-z0-9])[Ss]0*(\\d{1,2})[\\s._-]*[Ee]\\d+")
private val SEASON_WORD = Regex("[Ss]eason[\\s._-]*0*(\\d{1,2})")
private val SEASON_FA = Regex("فصل[\\s._-]*0*(\\d{1,2})")

/**
 * شماره‌ی فصل رو از اسم قسمت درمیاره (مثلاً S02.E05 یا Season 2 یا «فصل ۲»).
 * اگه چیزی پیدا نشد صفر برمی‌گردونه. فقط وقتی استفاده می‌شه که فصل از پنل ثبت نشده باشه.
 */
fun detectSeasonFromLabel(label: String): Int {
    if (label.isBlank()) return 0
    val text = buildString {
        for (c in label) {
            append(
                when (c) {
                    in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                    in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                    else -> c
                }
            )
        }
    }
    val match = SEASON_SXXEXX.find(text) ?: SEASON_WORD.find(text) ?: SEASON_FA.find(text)
    return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
