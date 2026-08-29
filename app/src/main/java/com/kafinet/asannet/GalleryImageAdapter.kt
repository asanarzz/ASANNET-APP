package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ItemGalleryImageBinding

class GalleryImageAdapter(private val images: List<String>) :
    RecyclerView.Adapter<GalleryImageAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemGalleryImageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        Glide.with(holder.binding.imgSlide.context)
            .load(images[position])
            .into(holder.binding.imgSlide)
    }

    override fun getItemCount(): Int = images.size
}
