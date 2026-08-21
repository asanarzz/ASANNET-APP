package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kafinet.asannet.databinding.ItemCategoryBinding

data class CategoryEntry(
    val type: ContentType?,
    val label: String,
    val iconRes: Int,
    val bgRes: Int,
    val isSubmit: Boolean = false
)

class CategoryGridAdapter(
    private val items: List<CategoryEntry>,
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
        binding.iconBg.setBackgroundResource(item.bgRes)
        binding.txtLabel.text = item.label
        binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
