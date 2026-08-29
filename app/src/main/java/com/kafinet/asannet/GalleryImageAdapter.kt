package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.kafinet.asannet.databinding.ItemGalleryImageBinding

/** لیست افقی و قابل‌سوایپ عکس‌های یک آیتم چندعکسی (گالری). */
class GalleryImageAdapter(private val imageUrls: List<String>) :
    RecyclerView.Adapter<GalleryImageAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemGalleryImageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.layoutParams = ViewGroup.LayoutParams(
            parent.resources.displayMetrics.widthPixels, ViewGroup.LayoutParams.MATCH_PARENT
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        Glide.with(holder.binding.imgGallery.context)
            .load(imageUrls[position])
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: com.bumptech.glide.load.engine.GlideException?, model: Any?,
                    target: Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean
                ): Boolean {
                    holder.binding.progress.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable, model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean
                ): Boolean {
                    holder.binding.progress.visibility = View.GONE
                    return false
                }
            })
            .into(holder.binding.imgGallery)
    }

    override fun getItemCount(): Int = imageUrls.size
}
