package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kafinet.asannet.databinding.ItemCategoryBinding

data class CategoryEntry(
    val type: ContentType?,
    val label: String,
    val iconRes: Int,
    val bgRes: Int,
    val isSubmit: Boolean = false,
    /** true یعنی از آخرین باری که کاربر این دسته را باز کرده، موردی تازه به آن اضافه شده. */
    val hasNew: Boolean = false,
    /** true یعنی آیکون «مدیریت فایل» (فایل منیجر کامل). */
    val isFileManager: Boolean = false
)

class CategoryGridAdapter(
    private var items: List<CategoryEntry>,
    private val onClick: (CategoryEntry) -> Unit
) : RecyclerView.Adapter<CategoryGridAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding
        binding.imgIcon.setImageResource(item.iconRes)
        binding.txtLabel.text = item.label
        binding.dotNew.visibility = if (item.hasNew) View.VISIBLE else View.GONE
        binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    /** رفرش کردن لیست (برای به‌روزرسانی دایره‌های «جدید» بدون نیاز به ساخت دوباره‌ی آداپتر). */
    fun updateItems(newItems: List<CategoryEntry>) {
        items = newItems
        notifyDataSetChanged()
    }
}
