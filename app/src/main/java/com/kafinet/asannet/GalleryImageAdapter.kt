package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ItemGalleryImageBinding

/**
 * هر اسلاید می‌تونه یه عکس واقعی باشه (نمایش مستقیم)، یا یه فایل دیگه (صدا، ویدیو،
 * پی‌دی‌اف، اچ‌تی‌ام‌ال، هر چیز دیگه) که به‌جای نمایش، یه آیکون + برچسب نشون داده
 * می‌شه و با لمس، از طریق ContentOpener باز می‌شه.
 */
class GalleryImageAdapter(
    private val files: List<String>,
    private val onOpenFile: (String) -> Unit
) : RecyclerView.Adapter<GalleryImageAdapter.ViewHolder>() {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val audioExtensions = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac")
    private val videoExtensions = setOf("mp4", "mkv", "webm", "3gp", "m3u", "m3u8")

    inner class ViewHolder(val binding: ItemGalleryImageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val url = files[position]
        val cleanUrl = url.substringBefore("?").substringBefore("#").lowercase()
        val extension = cleanUrl.substringAfterLast('.', "")
        val context = holder.binding.root.context

        if (extension in imageExtensions) {
            holder.binding.imgSlide.visibility = View.VISIBLE
            holder.binding.layoutFileOverlay.visibility = View.GONE
            Glide.with(context).load(url).into(holder.binding.imgSlide)
        } else {
            holder.binding.imgSlide.visibility = View.GONE
            holder.binding.layoutFileOverlay.visibility = View.VISIBLE

            val (iconRes, labelRes) = when {
                extension in audioExtensions -> R.drawable.ic_music to R.string.gallery_file_audio
                extension in videoExtensions -> R.drawable.ic_video to R.string.gallery_file_video
                extension == "pdf" -> R.drawable.ic_docs to R.string.gallery_file_pdf
                extension == "html" || extension == "htm" -> R.drawable.ic_open_browser to R.string.gallery_file_html
                else -> R.drawable.ic_file to R.string.gallery_file_generic
            }
            holder.binding.imgFileIcon.setImageResource(iconRes)
            holder.binding.txtFileLabel.setText(labelRes)
            holder.binding.layoutFileOverlay.setOnClickListener { onOpenFile(url) }
        }
    }

    override fun getItemCount(): Int = files.size
}
