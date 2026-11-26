package com.kafinet.asannet

import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ItemFileEntryBinding
import com.kafinet.asannet.databinding.ItemFileGridBinding
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
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<FileRow> = emptyList()
    private val selected = LinkedHashSet<String>()

    /** در نتایج جستجو، مسیر پوشه‌ی هر مورد هم نشان داده می‌شود. */
    var showPath: Boolean = false

    /** نوع نمایش (FileViewMode.ICON / LIST / DETAIL) و اندازه (LARGE / MEDIUM / SMALL). */
    var style: Int = FileViewMode.DETAIL
    var scale: Int = FileViewMode.LARGE

    private class RowVH(val b: ItemFileEntryBinding) : RecyclerView.ViewHolder(b.root)
    private class GridVH(val b: ItemFileGridBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int): Int = if (style == FileViewMode.ICON) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 1) GridVH(ItemFileGridBinding.inflate(inf, parent, false))
        else RowVH(ItemFileEntryBinding.inflate(inf, parent, false))
    }

    override fun getItemCount(): Int = rows.size

    private fun dp(v: View, x: Int): Int = (x * v.resources.displayMetrics.density + 0.5f).toInt()

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        val f = row.file
        val isSel = selected.contains(f.absolutePath)

        if (holder is GridVH) {
            val b = holder.b
            val dm = b.root.resources.displayMetrics
            val span = FileViewMode.spanCount(scale)
            val size = (dm.widthPixels / span.toFloat() - 12 * dm.density).toInt()
            val lp = b.iconBox.layoutParams
            lp.width = size
            lp.height = size
            b.iconBox.layoutParams = lp
            b.txtName.text = f.name
            b.txtName.setTextSize(TypedValue.COMPLEX_UNIT_SP, when (scale) { FileViewMode.LARGE -> 13f; FileViewMode.MEDIUM -> 12f; else -> 11f })
            bindIcon(row, b.iconBox, b.imgThumb, b.imgFolder, b.txtExt, b.txtCheck, isSel)
            b.cellRoot.setBackgroundColor(if (isSel) Color.parseColor("#224F46E5") else Color.TRANSPARENT)
            b.cellRoot.setOnClickListener { onClick(row) }
            b.cellRoot.setOnLongClickListener { onLongClick(row); true }
            return
        }

        val b = (holder as RowVH).b
        val iconDp = when (scale) { FileViewMode.LARGE -> 48; FileViewMode.MEDIUM -> 40; else -> 32 }
        val lp = b.iconBox.layoutParams
        lp.width = dp(b.root, iconDp)
        lp.height = dp(b.root, iconDp)
        b.iconBox.layoutParams = lp
        val padV = when (scale) { FileViewMode.LARGE -> 8; FileViewMode.MEDIUM -> 6; else -> 3 }
        b.rowInner.setPadding(b.rowInner.paddingLeft, dp(b.root, padV), b.rowInner.paddingRight, dp(b.root, padV))
        b.txtName.setTextSize(TypedValue.COMPLEX_UNIT_SP, when (scale) { FileViewMode.LARGE -> 15f; FileViewMode.MEDIUM -> 14f; else -> 13f })

        b.txtName.text = f.name
        val detail = style == FileViewMode.DETAIL
        if (detail) {
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
            b.txtMeta.visibility = View.VISIBLE
        } else {
            b.txtMeta.visibility = View.GONE
        }

        bindIcon(row, b.iconBox, b.imgThumb, b.imgFolder, b.txtExt, b.txtCheck, isSel)
        b.rowRoot.setBackgroundColor(if (isSel) Color.parseColor("#224F46E5") else Color.TRANSPARENT)
        b.rowRoot.setOnClickListener { onClick(row) }
        b.rowRoot.setOnLongClickListener { onLongClick(row); true }
    }

    private fun bindIcon(
        row: FileRow, box: View, thumb: ImageView, folder: ImageView,
        ext: TextView, check: TextView, isSel: Boolean
    ) {
        val f = row.file
        val kind = FileOps.kindOf(f)
        ViewCompat.setBackgroundTintList(box, ColorStateList.valueOf(Color.parseColor(colorFor(kind))))
        thumb.visibility = View.GONE
        folder.visibility = View.GONE
        ext.visibility = View.GONE
        Glide.with(thumb).clear(thumb)
        when (kind) {
            FileKind.FOLDER -> folder.visibility = View.VISIBLE
            FileKind.IMAGE, FileKind.VIDEO -> {
                thumb.visibility = View.VISIBLE
                Glide.with(thumb).load(f).centerCrop().into(thumb)
            }
            else -> {
                ext.visibility = View.VISIBLE
                val e = f.extension.uppercase()
                ext.text = if (e.isEmpty()) "؟" else e.take(4)
            }
        }
        check.visibility = if (isSel) View.VISIBLE else View.GONE
        if (isSel) ViewCompat.setBackgroundTintList(check, ColorStateList.valueOf(Color.parseColor("#4F46E5")))
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

    /** همه‌ی موردهای بین اولین و آخرین مورد انتخاب‌شده را انتخاب می‌کند. */
    fun selectInterval(): Boolean {
        val idx = rows.indices.filter { selected.contains(rows[it].file.absolutePath) }
        if (idx.size < 2) return false
        for (i in idx.first()..idx.last()) selected.add(rows[i].file.absolutePath)
        notifyDataSetChanged()
        return true
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
    }

    fun isSelected(row: FileRow) = selected.contains(row.file.absolutePath)
}
