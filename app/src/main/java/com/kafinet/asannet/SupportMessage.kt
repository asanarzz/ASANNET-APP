package com.kafinet.asannet

data class SupportMessage(
    val id: Long,
    val sender: String,
    val operatorName: String?,
    val message: String?,
    val attachmentUrl: String?,
    val attachmentType: String?,
    val attachmentName: String?,
    val createdAt: String
)
