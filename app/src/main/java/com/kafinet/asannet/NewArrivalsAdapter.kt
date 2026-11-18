package com.kafinet.asannet

import android.content.Context
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ItemNewArrivalBinding

/** نوع اتفاقی که باعث شده این مورد تو بنر «جدیدها» بیاد. */
enum class ArrivalKind { NEW_MOVIE, NEW_SERIES, NEW_EPISODE }

data class NewArrival(
    val item: ContentItem,
    val kind: ArrivalKind,
    /** زمان آخرین اتفاق (اضافه‌شدن یا اضافه‌شدن قسمت جدید) به میلی‌ثانیه. */
    val timestamp: Long
)

/**
 * بنر «جدیدها» بالای صفحه‌ی فیلم و سریال: پوستر مورد تازه‌اضافه‌شده + برچسبی که می‌گه
 * فیلم جدید، سریال جدید یا قسمت جدید اضافه شده.
 */
class NewArrivalsAdapter(
    private var arrivals: List<NewArrival>,
    private val onClick: (ContentItem) -> Unit
) : RecyclerView.Adapter<NewArrivalsAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemNewArrivalBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNewArrivalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val arrival = arrivals[position]
        val item = arrival.item
        val context = holder.binding.root.context

        val badgeRes = when (arrival.kind) {
            ArrivalKind.NEW_MOVIE -> R.string.new_arrival_movie
            ArrivalKind.NEW_SERIES -> R.string.new_arrival_series
            ArrivalKind.NEW_EPISODE -> R.string.new_arrival_episode
        }
        holder.binding.txtBadge.setText(badgeRes)

        holder.binding.txtTitle.text =
            if (item.locked) TextUtils.concat("🔒 ", forceEnglishDigits(item.title))
            else forceEnglishDigits(item.title)

        holder.binding.txtWhen.text = whenText(context, arrival.timestamp)

        val posterUrl = item.images.firstOrNull() ?: item.url
        Glide.with(holder.binding.imgPoster.context)
            .load(posterUrl)
            .centerCrop()
            .placeholder(R.drawable.ic_video)
            .into(holder.binding.imgPoster)

        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = arrivals.size

    fun updateItems(newArrivals: List<NewArrival>) {
        arrivals = newArrivals
        notifyDataSetChanged()
    }

    private fun whenText(context: Context, timestamp: Long): String {
        val days = ((System.currentTimeMillis() - timestamp) / DAY_MS).toInt()
        return when {
            days <= 0 -> context.getString(R.string.new_arrival_today)
            days == 1 -> context.getString(R.string.new_arrival_yesterday)
            else -> context.getString(R.string.new_arrival_days_ago, days)
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
