package com.kafinet.asannet

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

    fun formatDate(year: Int, month: Int, day: Int): String {
        val y = year.toString()
        val m = month.toString().padStart(2, '0')
        val d = day.toString().padStart(2, '0')
        return "$y/$m/$d"
    }

    private val jalaaliBreaks = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
        1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
    )

    private class JalCal(val leap: Int, val gy: Int, val march: Int)

    private fun jalCal(jy: Int): JalCal {
        val bl = jalaaliBreaks.size
        val gy = jy + 621
        var leapJ = -14
        var jp = jalaaliBreaks[0]
        var jump = 0
        var i = 1
        while (i < bl) {
            val jm = jalaaliBreaks[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += (jump / 33) * 8 + ((jump % 33) / 4)
            jp = jm
            i += 1
        }
        var n = jy - jp
        leapJ += (n / 33) * 8 + ((n % 33 + 3) / 4)
        if (jump % 33 == 4 && jump - n == 4) leapJ += 1

        val leapG = gy / 4 - ((gy / 100 + 1) * 3) / 4 - 150
        val march = 20 + leapJ - leapG

        if (jump - n < 6) {
            n = n - jump + ((jump + 4) / 33) * 33
        }
        var leap = ((n + 1) % 33 - 1) % 4
        if (leap == -1) leap = 4

        return JalCal(leap, gy, march)
    }

    private fun g2d(gy: Int, gm: Int, gd: Int): Long {
        var d = ((gy + (gm - 8) / 6 + 100100).toLong() * 1461) / 4 +
            (153L * ((gm + 9) % 12) + 2) / 5 +
            gd - 34840408L
        d -= (((gy + 100100 + (gm - 8) / 6) / 100).toLong() * 3) / 4 - 752
        return d
    }

    private fun d2gYear(jdn: Long): Int {
        var j = 4 * jdn + 139361631L
        j += (((4 * jdn + 183187720L) / 146097L) * 3 / 4) * 4 - 3908
        val i = ((j % 1461) / 4) * 5 + 308
        val gm = ((i / 153) % 12 + 1).toInt()
        return (j / 1461 - 100100 + (8 - gm) / 6).toInt()
    }

    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): IntArray {
        val jdn = g2d(gy, gm, gd)
        var jy = d2gYear(jdn) - 621
        val r = jalCal(jy)
        val jdn1f = g2d(r.gy, 3, r.march)
        var k = jdn - jdn1f
        val jm: Int
        val jd: Int
        if (k >= 0) {
            if (k <= 185) {
                jm = 1 + (k / 31).toInt()
                jd = (k % 31).toInt() + 1
                return intArrayOf(jy, jm, jd)
            }
            k -= 186
        } else {
            jy -= 1
            k += 179
            if (r.leap == 1) k += 1
        }
        jm = 7 + (k / 30).toInt()
        jd = (k % 30).toInt() + 1
        return intArrayOf(jy, jm, jd)
    }

    fun todayJalali(): IntArray {
        val cal = java.util.Calendar.getInstance()
        return gregorianToJalali(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    fun todayGregorian(): IntArray {
        val cal = java.util.Calendar.getInstance()
        return intArrayOf(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }
}
