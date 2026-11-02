package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kafinet.asannet.databinding.ItemDownloadBinding

class DownloadsAdapter(
    private var items: List<Pair<DownloadRecord, DownloadStatus>>,
    private val onClick: (DownloadRecord, DownloadStatus) -> Unit,
    private val onRemove: (DownloadRecord) -> Unit,
    private val onToggle: (DownloadRecord, DownloadStatus) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    private fun mb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (record, status) = items[position]
        val context = holder.binding.root.context
        holder.binding.txtDownloadTitle.text = record.title

        val progress = if (status.bytesTotal > 0) {
            ((status.bytesDownloaded * 100) / status.bytesTotal).toInt().coerceIn(0, 100)
        } else 0

        when (status.state) {
            DownloadState.SUCCESSFUL -> {
                holder.binding.txtDownloadStatus.text = context.getString(R.string.download_status_done)
                holder.binding.progressDownload.visibility = View.GONE
            }
            DownloadState.RUNNING, DownloadState.PENDING -> {
                holder.binding.txtDownloadStatus.text = context.getString(R.string.download_status_running)
                holder.binding.progressDownload.visibility = View.VISIBLE
                holder.binding.progressDownload.isIndeterminate = status.bytesTotal <= 0
                holder.binding.progressDownload.progress = progress
            }
            DownloadState.PAUSED -> {
                holder.binding.txtDownloadStatus.text = context.getString(R.string.download_status_paused)
                holder.binding.progressDownload.visibility = View.VISIBLE
                holder.binding.progressDownload.isIndeterminate = false
                holder.binding.progressDownload.progress = progress
            }
            DownloadState.LISTED -> {
                holder.binding.txtDownloadStatus.text = ""
                holder.binding.progressDownload.visibility = View.GONE
            }
            DownloadState.FAILED -> {
                holder.binding.txtDownloadStatus.text = context.getString(R.string.download_status_failed)
                holder.binding.progressDownload.visibility = View.GONE
            }
            DownloadState.UNKNOWN -> {
                holder.binding.txtDownloadStatus.text = context.getString(R.string.download_status_done)
                holder.binding.progressDownload.visibility = View.GONE
            }
        }

        if (status.message.isNotBlank()) {
            // دانلودِ موتور خود برنامه: متن آماده + درصد و حجم
            var text = status.message
            if (status.bytesTotal > 0L && status.state != DownloadState.SUCCESSFUL) {
                text += "\n" + progress + "٪ - " + mb(status.bytesDownloaded) + " از " +
                    mb(status.bytesTotal) + " مگابایت"
            }
            holder.binding.txtDownloadStatus.text = text
            if (status.state == DownloadState.FAILED && status.bytesTotal > 0L) {
                holder.binding.progressDownload.visibility = View.VISIBLE
                holder.binding.progressDownload.isIndeterminate = false
                holder.binding.progressDownload.progress = progress
            }
        } else {
            // دانلودِ قدیمیِ دانلودر سیستم: دلیل گیر کردن/ناموفق بودن (اگه باشه) زیر وضعیت نوشته می‌شه
            AppDownloadManager.describeProblem(status)?.let { problem ->
                holder.binding.txtDownloadStatus.text =
                    holder.binding.txtDownloadStatus.text.toString() + "\n" + problem
            }
        }

        // دکمه‌ی توقف / ادامه (فقط برای دانلودهای موتور خود برنامه)
        val canToggle = record.isApp && (
            status.state == DownloadState.RUNNING ||
                status.state == DownloadState.PAUSED ||
                status.state == DownloadState.LISTED ||
                status.state == DownloadState.FAILED
            )
        holder.binding.btnToggleDownload.visibility = if (canToggle) View.VISIBLE else View.GONE
        holder.binding.btnToggleDownload.setImageResource(
            if (status.state == DownloadState.RUNNING) R.drawable.ic_pause else R.drawable.ic_play
        )
        holder.binding.btnToggleDownload.setOnClickListener { onToggle(record, status) }

        holder.binding.root.setOnClickListener { onClick(record, status) }
        holder.binding.btnRemoveDownload.setOnClickListener { onRemove(record) }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<Pair<DownloadRecord, DownloadStatus>>) {
        items = newItems
        notifyDataSetChanged()
    }
}
