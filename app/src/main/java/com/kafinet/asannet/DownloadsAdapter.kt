package com.kafinet.asannet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kafinet.asannet.databinding.ItemDownloadBinding

class DownloadsAdapter(
    private var items: List<Pair<DownloadRecord, DownloadStatus>>,
    private val onClick: (DownloadRecord, DownloadStatus) -> Unit,
    private val onRemove: (DownloadRecord) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (record, status) = items[position]
        val context = holder.binding.root.context
        holder.binding.txtDownloadTitle.text = record.title

        val progress = if (status.bytesTotal > 0) {
            ((status.bytesDownloaded * 100) / status.bytesTotal).toInt()
        } else 0

        when (status.state) {
            DownloadState.SUCCESSFUL -> {
                holder.binding.txtDownloadStatus.text = context.getString(R.string.download_status_done)
                holder.binding.progressDownload.visibility = android.view.View.GONE
            }
            DownloadState.RUNNING, DownloadState.PENDING -> {
                holder.binding.txtDownloadStatus.text = context.getString(R.string.download_status_running)
                holder.binding.progressDownload.visibility = android.view.View.VISIBLE
                holder.binding.progressDownload.isIndeterminate = status.bytesTotal <= 0
                holder.binding.progressDownload.progress = progress
            }
            DownloadState.PAUSED -> {
                holder.binding.txtDownloadStatus.text = context.getString(R.string.download_status_paused)
                holder.binding.progressDownload.visibility = android.view.View.VISIBLE
                holder.binding.progressDownload.isIndeterminate = false
                holder.binding.progressDownload.progress = progress
            }
            DownloadState.FAILED -> {
                holder.binding.txtDownloadStatus.text = context.getString(R.string.download_status_failed)
                holder.binding.progressDownload.visibility = android.view.View.GONE
            }
            DownloadState.UNKNOWN -> {
                holder.binding.txtDownloadStatus.text = context.getString(R.string.download_status_done)
                holder.binding.progressDownload.visibility = android.view.View.GONE
            }
        }

        holder.binding.root.setOnClickListener { onClick(record, status) }
        holder.binding.btnRemoveDownload.setOnClickListener { onRemove(record) }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<Pair<DownloadRecord, DownloadStatus>>) {
        items = newItems
        notifyDataSetChanged()
    }
}
