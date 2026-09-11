package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ItemPosterBinding

/**
 * ردیف افقی پوستر فیلم/سریال (برای صفحه‌ی دسته‌ی «فیلم» با دو بخش فیلم و سریال).
 */
class PosterCarouselAdapter(
    private var items: List<ContentItem>,
    private val onClick: (ContentItem) -> Unit
) : RecyclerView.Adapter<PosterCarouselAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemPosterBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.txtPosterTitle.text = item.title

        val posterUrl = item.images.firstOrNull() ?: item.url
        Glide.with(holder.binding.imgPoster.context)
            .load(posterUrl)
            .centerCrop()
            .placeholder(R.drawable.ic_video)
            .into(holder.binding.imgPoster)

        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<ContentItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
