package com.kafinet.asannet

/**
 * توابع کمکی برای کار با تاریخ شمسی، بدون نیاز به کتابخونه‌ی بیرونی.
 * فقط برای نمایش/انتخاب تاریخ لازم است (نه تبدیل از میلادی)، پس یک
 * الگوریتم ساده و رایج برای سال کبیسه (چرخه‌ی ۳۳ ساله) کافی است.
 */
object PersianDateUtils {

    val monthNames = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    private val leapRemainders = setOf(1, 5, 9, 13, 17, 22, 26, 30)

    fun isLeapYear(year: Int): Boolean {
        val mod = ((year % 33) + 33) % 33
        return mod in leapRemainders
    }

    fun daysInMonth(year: Int, month: Int): Int = when {
        month in 1..6 -> 31
        month in 7..11 -> 30
        month == 12 -> if (isLeapYear(year)) 30 else 29
        else -> 30
    }

    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun toPersianDigits(input: String): String {
        val sb = StringBuilder()
        for (ch in input) {
            if (ch in '0'..'9') sb.append(persianDigits[ch - '0']) else sb.append(ch)
        }
        return sb.toString()
    }

    /** مثلاً formatDate(1370, 5, 2) -> "1370/05/02" */
    fun formatDate(year: Int, month: Int, day: Int): String {
        val y = year.toString()
        val m = month.toString().padStart(2, '0')
        val d = day.toString().padStart(2, '0')
        return "$y/$m/$d"
    }
}
