package com.kafinet.asannet

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ItemFileEntryBinding
import java.io.File

/** یک ردیف در لیست فایل منیجر. */
data class FileRow(
    val file: File,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val childCount: Int
)

class FileManagerAdapter(
    private val onClick: (FileRow) -> Unit,
    private val onLongClick: (FileRow) -> Unit
) : RecyclerView.Adapter<FileManagerAdapter.VH>() {

    private var rows: List<FileRow> = emptyList()
    private val selected = LinkedHashSet<String>()

    /** در نتایج جستجو، مسیر پوشه‌ی هر مورد هم نشان داده می‌شود. */
    var showPath: Boolean = false

    inner class VH(val b: ItemFileEntryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFileEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val b = holder.b
        val f = row.file
        val kind = FileOps.kindOf(f)

        b.txtName.text = f.name
        val meta = StringBuilder()
        if (row.isDir) {
            meta.append(PersianDateUtils.toPersianDigits(row.childCount.toString())).append(" مورد")
        } else {
            meta.append(FileOps.formatSize(row.size))
        }
        val date = FileOps.formatDate(row.modified)
        if (date.isNotEmpty()) meta.append("  •  ").append(date)
        if (showPath) meta.append("\n").append(f.parent ?: "")
        b.txtMeta.maxLines = if (showPath) 2 else 1
        b.txtMeta.text = meta.toString()

        // آیکون
        ViewCompat.setBackgroundTintList(b.iconBox, ColorStateList.valueOf(Color.parseColor(colorFor(kind))))
        b.imgThumb.visibility = View.GONE
        b.imgFolder.visibility = View.GONE
        b.txtExt.visibility = View.GONE
        Glide.with(b.imgThumb).clear(b.imgThumb)

        when (kind) {
            FileKind.FOLDER -> b.imgFolder.visibility = View.VISIBLE
            FileKind.IMAGE, FileKind.VIDEO -> {
                b.imgThumb.visibility = View.VISIBLE
                Glide.with(b.imgThumb).load(f).centerCrop().into(b.imgThumb)
            }
            else -> {
                b.txtExt.visibility = View.VISIBLE
                val ext = f.extension.uppercase()
                b.txtExt.text = if (ext.isEmpty()) "؟" else ext.take(4)
            }
        }

        val isSel = selected.contains(f.absolutePath)
        b.txtCheck.visibility = if (isSel) View.VISIBLE else View.GONE
        if (isSel) ViewCompat.setBackgroundTintList(b.txtCheck, ColorStateList.valueOf(Color.parseColor("#4F46E5")))
        b.rowRoot.setBackgroundColor(if (isSel) Color.parseColor("#224F46E5") else Color.TRANSPARENT)

        b.rowRoot.setOnClickListener { onClick(row) }
        b.rowRoot.setOnLongClickListener { onLongClick(row); true }
    }

    private fun colorFor(kind: FileKind): String = when (kind) {
        FileKind.FOLDER -> "#7C3AED"
        FileKind.IMAGE -> "#059669"
        FileKind.VIDEO -> "#DC2626"
        FileKind.AUDIO -> "#EA580C"
        FileKind.PDF -> "#E11D48"
        FileKind.DOC -> "#2563EB"
        FileKind.ARCHIVE -> "#D97706"
        FileKind.APK -> "#65A30D"
        FileKind.OTHER -> "#6B7280"
    }

    fun submit(newRows: List<FileRow>) {
        rows = newRows
        // انتخاب‌هایی که دیگر در لیست نیستند حذف شوند
        val paths = newRows.map { it.file.absolutePath }.toHashSet()
        selected.retainAll(paths)
        notifyDataSetChanged()
    }

    val isSelecting: Boolean get() = selected.isNotEmpty()
    val selectedCount: Int get() = selected.size
    fun selectedFiles(): List<File> = selected.map { File(it) }

    fun toggle(row: FileRow) {
        val p = row.file.absolutePath
        if (!selected.remove(p)) selected.add(p)
        val i = rows.indexOfFirst { it.file.absolutePath == p }
        if (i >= 0) notifyItemChanged(i)
    }

    fun selectAll() {
        rows.forEach { selected.add(it.file.absolutePath) }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
    }

    fun isSelected(row: FileRow) = selected.contains(row.file.absolutePath)
}
