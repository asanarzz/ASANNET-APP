package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ItemBannerBinding

/**
 * لیست بنرهای تبلیغاتی بالای صفحه‌ی اصلی — تعدادشون کاملاً متغیره (از پنل مدیریت
 * قابل افزودن/حذفه)، هر تعدادی که باشه همینجا نمایش داده می‌شه.
 */
class BannerCarouselAdapter(
    private var items: List<ContentItem>,
    private val onClick: (ContentItem) -> Unit
) : RecyclerView.Adapter<BannerCarouselAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemBannerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBannerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        Glide.with(holder.binding.imgBanner.context)
            .load(item.url)
            .centerCrop()
            .into(holder.binding.imgBanner)

        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.btnMore.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<ContentItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
