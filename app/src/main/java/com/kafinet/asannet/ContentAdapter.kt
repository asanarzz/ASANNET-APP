package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kafinet.asannet.databinding.ItemContentBinding

class ContentAdapter(
    private var items: List<ContentItem>,
    private val onClick: (ContentItem) -> Unit
) : RecyclerView.Adapter<ContentAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemContentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding

        binding.txtTitle.text = item.title
        binding.txtDesc.text = item.description
        binding.txtDesc.visibility = if (item.description.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

        val (iconRes, bgRes) = when (item.type) {
            ContentType.TEST -> R.drawable.ic_test to R.drawable.icon_circle_test
            ContentType.LINK -> R.drawable.ic_link to R.drawable.icon_circle_link
            ContentType.IMAGE -> R.drawable.ic_image to R.drawable.icon_circle_image
            ContentType.VIDEO -> R.drawable.ic_video to R.drawable.icon_circle_video
            ContentType.FILE -> R.drawable.ic_file to R.drawable.icon_circle_file
        }
        binding.imgTypeIcon.setImageResource(iconRes)
        binding.iconBg.setBackgroundResource(bgRes)

        binding.rowRoot.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<ContentItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
