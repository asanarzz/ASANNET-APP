package com.kafinet.asannet

import android.view.Gravity
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ItemSupportMessageBinding

class SupportMessageAdapter(
    private var items: List<SupportMessage>,
    private val onAttachmentClick: (SupportMessage) -> Unit
) : RecyclerView.Adapter<SupportMessageAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemSupportMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSupportMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.binding.root.context
        val isUser = item.sender == "user"

        val lp = holder.binding.bubble.layoutParams as android.widget.LinearLayout.LayoutParams
        lp.gravity = if (isUser) Gravity.START else Gravity.END
        holder.binding.bubble.layoutParams = lp

        if (isUser) {
            holder.binding.bubble.setBackgroundResource(R.drawable.bubble_user)
            holder.binding.txtMessage.setTextColor(ContextCompat.getColor(context, R.color.white))
            holder.binding.txtAttachmentName.setTextColor(ContextCompat.getColor(context, R.color.white))
            holder.binding.txtOperatorName.visibility = android.view.View.GONE
        } else {
            holder.binding.bubble.setBackgroundResource(R.drawable.bubble_admin)
            holder.binding.txtMessage.setTextColor(ContextCompat.getColor(context, R.color.text_main))
            holder.binding.txtAttachmentName.setTextColor(ContextCompat.getColor(context, R.color.primary))
            if (!item.operatorName.isNullOrBlank()) {
                holder.binding.txtOperatorName.visibility = android.view.View.VISIBLE
                holder.binding.txtOperatorName.text =
                    context.getString(R.string.support_operator_label, item.operatorName)
            } else {
                holder.binding.txtOperatorName.visibility = android.view.View.GONE
            }
        }

        if (item.message.isNullOrBlank()) {
            holder.binding.txtMessage.visibility = android.view.View.GONE
        } else {
            holder.binding.txtMessage.visibility = android.view.View.VISIBLE
            holder.binding.txtMessage.text = forceEnglishDigits(item.message)
        }

        holder.binding.imgAttachment.visibility = android.view.View.GONE
        holder.binding.layoutFileAttachment.visibility = android.view.View.GONE

        if (!item.attachmentUrl.isNullOrBlank()) {
            when (item.attachmentType) {
                "image" -> {
                    holder.binding.imgAttachment.visibility = android.view.View.VISIBLE
                    Glide.with(context).load(item.attachmentUrl).into(holder.binding.imgAttachment)
                }
                else -> {
                    holder.binding.layoutFileAttachment.visibility = android.view.View.VISIBLE
                    holder.binding.txtAttachmentName.text =
                        item.attachmentName ?: context.getString(R.string.support_attachment_generic)
                }
            }
            holder.binding.root.setOnClickListener { onAttachmentClick(item) }
            holder.binding.bubble.setOnClickListener { onAttachmentClick(item) }
        } else {
            holder.binding.root.setOnClickListener(null)
            holder.binding.bubble.setOnClickListener(null)
        }

        holder.binding.txtTime.text = formatMessageTime(item.createdAt)
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<SupportMessage>) {
        items = newItems
        notifyDataSetChanged()
    }

    private fun formatMessageTime(iso: String): String {
        return try {
            val cleaned = iso.substringBefore(".").substringBefore("+")
            val parts = cleaned.split("T")
            val timePart = parts.getOrNull(1) ?: return ""
            val hm = timePart.split(":")
            if (hm.size >= 2) "${hm[0]}:${hm[1]}" else ""
        } catch (e: Exception) {
            ""
        }
    }
}
