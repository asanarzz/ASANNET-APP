package com.kafinet.asannet

import org.json.JSONArray
import org.json.JSONObject

enum class ContentType(val key: String) {
    IMAGE("image"),
    VIDEO("video"),
    BANNER("banner"),
    LINK("link"),
    FILE("file"),
    TEST("test"),
    POLL("poll"),
    SOFTWARE("software"),
    MUSIC("music"),
    RADIO("radio"),
    FUN("fun"),
    DOCS("docs");

    companion object {
        fun fromKey(key: String?): ContentType =
            values().firstOrNull { it.key.equals(key, ignoreCase = true) } ?: LINK
    }
}

data class ContentItem(
    val id: String,
    val type: ContentType,
    val title: String,
    val description: String,
    val url: String
) {
    companion object {
        fun listFromJson(jsonText: String): List<ContentItem> {
            val array = JSONArray(jsonText)
            val result = mutableListOf<ContentItem>()
            for (i in 0 until array.length()) {
                val obj: JSONObject = array.getJSONObject(i)
                result.add(
                    ContentItem(
                        id = obj.optString("id", "item_$i"),
                        type = ContentType.fromKey(obj.optString("type")),
                        title = obj.optString("title", ""),
                        description = obj.optString("description", ""),
                        url = obj.optString("url", "")
                    )
                )
            }
            return result
        }
    }
}
