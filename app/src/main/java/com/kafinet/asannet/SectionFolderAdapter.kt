package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kafinet.asannet.databinding.ItemSectionFolderBinding

/**
 * یک بخش (پوشه) داخل یک دسته — مثلاً «آزمون‌های غربالگری» زیرِ دسته‌ی «آزمون».
 * name=null یعنی موارد بدون بخش مشخص (نمایش داده می‌شوند زیر برچسب «سایر»).
 */
data class SectionFolder(val name: String?, val displayName: String, val count: Int)

class SectionFolderAdapter(
    private var folders: List<SectionFolder>,
    private val onClick: (SectionFolder) -> Unit
) : RecyclerView.Adapter<SectionFolderAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemSectionFolderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSectionFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        holder.binding.txtSectionName.text = folder.displayName
        val context = holder.binding.root.context
        holder.binding.txtSectionCount.text =
            String.format(java.util.Locale.US, context.getString(R.string.section_item_count), folder.count)
        // فونت سفارشی برنامه تو محیط راست‌به‌چپ اعداد رو خودکار به فارسی تبدیل می‌کنه؛
        // برای این‌که تعداد همیشه انگلیسی نشون داده بشه، جهت/زبان/فونتش رو صریح می‌کنیم.
        holder.binding.txtSectionCount.textDirection = android.view.View.TEXT_DIRECTION_LTR
        holder.binding.txtSectionCount.textLocales = android.os.LocaleList(java.util.Locale.US)
        holder.binding.txtSectionCount.fontFeatureSettings = "tnum"
        holder.binding.rowRoot.setOnClickListener { onClick(folder) }
    }

    override fun getItemCount(): Int = folders.size

    fun updateFolders(newFolders: List<SectionFolder>) {
        folders = newFolders
        notifyDataSetChanged()
    }
}
