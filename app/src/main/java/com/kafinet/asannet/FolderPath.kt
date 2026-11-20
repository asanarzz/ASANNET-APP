package com.kafinet.asannet

/**
 * پوشه در پوشه: مسیر پوشه داخل همان فیلد «section» نگهداری می‌شود و بخش‌های آن با «/» جدا می‌شوند.
 * مثلاً «آزمون‌ها/غربالگری/کودکان» یعنی پوشه‌ی «کودکان» داخل «غربالگری» داخل «آزمون‌ها».
 * آیتم‌های قدیمی (بدون «/») مثل قبل یک پوشه‌ی تک‌سطحی هستند.
 */
object FolderPath {
    const val SEP = "/"

    /** فاصله‌های اضافه و بخش‌های خالی را حذف می‌کند؛ اگر چیزی نماند null برمی‌گرداند. */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val parts = raw.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) null else parts.joinToString(SEP)
    }

    /** اسم آخرین پوشه‌ی مسیر (چیزی که کاربر روی کارت پوشه می‌بیند). */
    fun leaf(path: String): String = path.substringAfterLast(SEP)

    /** اولین بخش مسیر. */
    fun first(path: String): String = path.substringBefore(SEP)

    /** مسیر والد؛ برای پوشه‌ی سطح اول null. */
    fun parent(path: String): String? =
        if (path.contains(SEP)) path.substringBeforeLast(SEP) else null

    /** آیا [section] خودِ [base] یا داخل زیرمجموعه‌ی آن است؟ */
    fun isUnder(section: String?, base: String): Boolean =
        section != null && (section == base || section.startsWith(base + SEP))

    /** از شناسه‌ی گروه قفل («folder:نوع|مسیر») مسیر پوشه را درمی‌آورد. */
    fun pathOfLockGroup(group: String): String = group.substringAfter('|', "")
}
