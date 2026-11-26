package com.kafinet.asannet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** نوع فایل برای انتخاب آیکون/رنگ در فایل منیجر. */
enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOC, ARCHIVE, APK, OTHER }

/**
 * عملیات روی فایل‌ها برای فایل منیجر: کپی/جابه‌جایی/حذف/زیپ/باز کردن/اشتراک‌گذاری و...
 * همه‌ی تابع‌های سنگین باید روی ترد پس‌زمینه صدا زده بشن.
 */
object FileOps {

    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "m4v", "ts", "wmv")
    private val audioExt = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "wma")
    private val docExt = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "odt", "html", "htm", "xml", "json")
    private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2")

    fun kindOf(file: File): FileKind {
        if (file.isDirectory) return FileKind.FOLDER
        return when (file.extension.lowercase()) {
            in imageExt -> FileKind.IMAGE
            in videoExt -> FileKind.VIDEO
            in audioExt -> FileKind.AUDIO
            "pdf" -> FileKind.PDF
            in docExt -> FileKind.DOC
            in archiveExt -> FileKind.ARCHIVE
            "apk" -> FileKind.APK
            else -> FileKind.OTHER
        }
    }

    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "mkv" -> "video/x-matroska"
            "opus" -> "audio/ogg"
            else -> "*/*"
        }
    }

    /** «۱٫۲ مگابایت» و امثالش (با ارقام فارسی). */
    fun formatSize(bytes: Long): String {
        val units = arrayOf("بایت", "کیلوبایت", "مگابایت", "گیگابایت", "ترابایت")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        val text = if (i == 0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
        return PersianDateUtils.toPersianDigits(text) + " " + units[i]
    }

    /** تاریخ شمسی + ساعت، مثلاً «۱۴۰۵/۰۹/۰۴ - ۱۳:۲۰». */
    fun formatDate(millis: Long): String {
        if (millis <= 0L) return ""
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val j = PersianDateUtils.gregorianToJalali(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        val time = String.format(
            java.util.Locale.US, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
        return PersianDateUtils.toPersianDigits(PersianDateUtils.formatDate(j[0], j[1], j[2]) + " - " + time)
    }

    fun freeAndTotal(dir: File): Pair<Long, Long>? = try {
        val s = StatFs(dir.absolutePath)
        Pair(s.availableBytes, s.totalBytes)
    } catch (e: Exception) {
        null
    }

    /** اگه فایل/پوشه‌ای با همین اسم هست، «اسم (1)» و... برمی‌گردونه. */
    fun uniqueDest(dir: File, name: String): File {
        var dest = File(dir, name)
        if (!dest.exists()) return dest
        val base = if (File(name).extension.isEmpty() || File(dir, name).isDirectory) name else name.removeSuffix("." + File(name).extension)
        val ext = if (File(dir, name).isDirectory || File(name).extension.isEmpty()) "" else "." + File(name).extension
        var i = 1
        while (dest.exists()) {
            dest = File(dir, "$base ($i)$ext")
            i++
        }
        return dest
    }

    /** آیا dir همان src است یا داخل src قرار دارد؟ (کپی یک پوشه داخل خودش ممنوع است) */
    fun isInside(dir: File, src: File): Boolean {
        val d = dir.canonicalPath
        val s = src.canonicalPath
        return d == s || d.startsWith(s + File.separator)
    }

    fun copyRecursive(src: File, dstDir: File, isCancelled: () -> Boolean = { false }): File? {
        if (isCancelled()) return null
        val dest = uniqueDest(dstDir, src.name)
        if (src.isDirectory) {
            if (!dest.mkdirs()) return null
            src.listFiles()?.forEach { copyRecursive(it, dest, isCancelled) }
        } else {
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest.setLastModified(src.lastModified())
        }
        return dest
    }

    /** اول rename (سریع، تو یک پارتیشن)؛ اگه نشد کپی + حذف مبدا. */
    fun moveTo(src: File, dstDir: File): Boolean {
        val dest = uniqueDest(dstDir, src.name)
        if (src.renameTo(dest)) return true
        val copied = copyRecursive(src, dstDir) ?: return false
        return deleteRecursive(src).also { if (!it) deleteRecursive(copied) }
    }

    fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        return file.delete()
    }

    fun folderSize(file: File, isCancelled: () -> Boolean = { false }): Long {
        if (isCancelled()) return 0L
        if (!file.isDirectory) return file.length()
        var total = 0L
        file.listFiles()?.forEach { total += folderSize(it, isCancelled) }
        return total
    }

    /** جستجوی بازگشتی اسم‌ها زیر root (حداکثر limit نتیجه). */
    fun search(root: File, query: String, showHidden: Boolean, limit: Int, isCancelled: () -> Boolean): List<File> {
        val out = ArrayList<File>()
        val q = query.lowercase()
        fun walk(dir: File) {
            if (isCancelled() || out.size >= limit) return
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (isCancelled() || out.size >= limit) return
                if (!showHidden && c.name.startsWith(".")) continue
                if (c.name.lowercase().contains(q)) out.add(c)
                if (c.isDirectory) walk(c)
            }
        }
        walk(root)
        return out
    }

    // ---------- زیپ ----------

    fun zip(files: List<File>, destZip: File, isCancelled: () -> Boolean = { false }): Boolean {
        return try {
            ZipOutputStream(FileOutputStream(destZip).buffered()).use { zos ->
                for (f in files) addToZip(zos, f, f.name, isCancelled)
            }
            !isCancelled()
        } catch (e: Exception) {
            destZip.delete()
            false
        }
    }

    private fun addToZip(zos: ZipOutputStream, file: File, entryName: String, isCancelled: () -> Boolean) {
        if (isCancelled()) return
        if (file.isDirectory) {
            val children = file.listFiles().orEmpty()
            if (children.isEmpty()) {
                zos.putNextEntry(ZipEntry("$entryName/"))
                zos.closeEntry()
            }
            for (c in children) addToZip(zos, c, "$entryName/${c.name}", isCancelled)
        } else {
            zos.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
            FileInputStream(file).use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    /** استخراج zip داخل destDir (با محافظت در برابر مسیرهای خطرناک مثل ../). */
    fun unzip(zipFile: File, destDir: File, isCancelled: () -> Boolean = { false }): Boolean {
        return try {
            if (!destDir.exists() && !destDir.mkdirs()) return false
            val destCanon = destDir.canonicalPath
            ZipInputStream(FileInputStream(zipFile).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (isCancelled()) return false
                    val out = File(destDir, entry.name)
                    if (!out.canonicalPath.startsWith(destCanon + File.separator) && out.canonicalPath != destCanon) {
                        throw SecurityException("bad zip entry")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                        if (entry.time > 0) out.setLastModified(entry.time)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- باز کردن و اشتراک‌گذاری ----------

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun openFile(context: Context, file: File) = openFileAs(context, file, mimeOf(file))

    /** باز کردن فایل با نوع دلخواه (مثلاً «باز کردن به عنوان متن»). */
    fun openFileAs(context: Context, file: File, mime: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriFor(context, file), mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.err_app_not_installed, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareFiles(context: Context, files: List<File>) {
        val onlyFiles = files.filter { it.isFile }
        if (onlyFiles.isEmpty()) {
            Toast.makeText(context, R.string.fm_share_folder_hint, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = if (onlyFiles.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeOf(onlyFiles[0])
                    putExtra(Intent.EXTRA_STREAM, uriFor(context, onlyFiles[0]))
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        ArrayList(onlyFiles.map { uriFor(context, it) })
                    )
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.fm_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, R.string.error_loading, Toast.LENGTH_SHORT).show()
        }
    }
}
