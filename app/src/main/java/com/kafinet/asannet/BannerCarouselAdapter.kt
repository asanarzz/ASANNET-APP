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

    // اگه صفر باشه، اندازه‌ی ثابت تعریف‌شده در item_banner.xml استفاده می‌شه.
    // در غیر این صورت، اندازه‌ی هر کارت متناسب با عرض واقعی صفحه محاسبه و جایگزین می‌شه
    // (نسبت ۲ به ۱، هم‌راستا با سایز پیشنهادی تصویر بنرها یعنی ۱۶۰۰×۸۰۰).
    private var itemWidthPx: Int = 0

    inner class ViewHolder(val binding: ItemBannerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBannerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        if (itemWidthPx > 0) {
            val params = holder.binding.root.layoutParams
            params.width = itemWidthPx
            // نسبت بنر ۱۶۰۰ در ۱۲۰۰ (یعنی ارتفاع برابر ۷۵٪ عرض)
            params.height = (itemWidthPx * 0.75).toInt()
            holder.binding.root.layoutParams = params
        }

        val imageUrl = item.images.firstOrNull() ?: item.url
        Glide.with(holder.binding.imgBanner.context)
            .load(imageUrl)
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

    /** عرض هر کارت بنر را بر حسب پیکسل ست می‌کند (برای پر کردن بهتر عرض صفحه در هر گوشی). */
    fun setItemWidth(widthPx: Int) {
        if (widthPx > 0 && widthPx != itemWidthPx) {
            itemWidthPx = widthPx
            notifyDataSetChanged()
        }
    }
}

