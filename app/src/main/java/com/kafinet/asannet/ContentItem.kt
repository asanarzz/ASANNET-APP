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
    DOCS("docs"),
    POWER_OUTAGE("power_outage"),
    NEWSPAPER("newspaper"),
    PRICE("price"),
    SERVICES("services"),
    HOME_BANNER("home_banner");

    companion object {
        fun fromKey(key: String?): ContentType =
            values().firstOrNull { it.key.equals(key, ignoreCase = true) } ?: LINK
    }
}

data class DownloadLink(val label: String, val url: String)

data class ContentItem(
    val id: String,
    val type: ContentType,
    val title: String,
    val description: String,
    val url: String,
    val section: String? = null,
    val images: List<String> = emptyList(),
    val links: List<DownloadLink> = emptyList()
) {
    companion object {
        fun listFromJson(jsonText: String): List<ContentItem> {
            val array = JSONArray(jsonText)
            val result = mutableListOf<ContentItem>()
            for (i in 0 until array.length()) {
                val obj: JSONObject = array.getJSONObject(i)
                val sectionValue = obj.optString("section", "").trim()
                val imagesArray = obj.optJSONArray("images")
                val images = mutableListOf<String>()
                if (imagesArray != null) {
                    for (j in 0 until imagesArray.length()) {
                        val imgUrl = imagesArray.optString(j, "").trim()
                        if (imgUrl.isNotBlank()) images.add(imgUrl)
                    }
                }
                val linksArray = obj.optJSONArray("links")
                val links = mutableListOf<DownloadLink>()
                if (linksArray != null) {
                    for (k in 0 until linksArray.length()) {
                        val linkObj = linksArray.optJSONObject(k) ?: continue
                        val linkUrl = linkObj.optString("url", "").trim()
                        if (linkUrl.isBlank()) continue
                        val linkLabel = linkObj.optString("label", "").trim()
                        links.add(DownloadLink(linkLabel, linkUrl))
                    }
                }
                result.add(
                    ContentItem(
                        id = obj.optString("id", "item_$i"),
                        type = ContentType.fromKey(obj.optString("type")),
                        title = obj.optString("title", ""),
                        description = obj.optString("description", ""),
                        url = obj.optString("url", ""),
                        section = if (sectionValue.isBlank()) null else sectionValue,
                        images = images,
                        links = links
                    )
                )
            }
            return result
        }
    }
}
            }
            return result
        }
    }
}
