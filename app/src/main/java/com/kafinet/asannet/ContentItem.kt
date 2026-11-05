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
    TV("tv"),
    FUN("fun"),
    DOCS("docs"),
    POWER_OUTAGE("power_outage"),
    NEWSPAPER("newspaper"),
    PRICE("price"),
    SERVICES("services"),
    WEATHER("weather"),
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
    val links: List<DownloadLink> = emptyList(),
    /** true یعنی لینک/عکس واقعی در content.json نیست و فقط با توکن از سرور گرفته می‌شود. */
    val locked: Boolean = false,
    /** اگر آیتم عضو یک پوشه‌ی قفل‌شده باشد، شناسه‌ی پوشه (برای توکن پوشه). */
    val lockGroup: String? = null,
    /** false یعنی دکمه‌ی «باز کردن در مرورگر بیرونی» برای این مورد مخفی بماند. */
    val allowExternalBrowser: Boolean = true,
    /** false یعنی این لینک با نسخه‌ی موبایل (نه دسکتاپ) نمایش داده شود. */
    val desktopMode: Boolean = true,
    /** true یعنی این مورد (یا پوشه‌ای که عضوشه) از پنل مدیریت پین شده و باید بالای لیست بیاد. */
    val pinned: Boolean = false
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
                val parsedItem =
                    ContentItem(
                        id = obj.optString("id", "item_$i"),
                        type = ContentType.fromKey(obj.optString("type")),
                        title = obj.optString("title", ""),
                        description = obj.optString("description", ""),
                        url = obj.optString("url", ""),
                        section = if (sectionValue.isBlank()) null else sectionValue,
                        images = images,
                        links = links,
                        locked = obj.optBoolean("locked", false),
                        lockGroup = obj.optString("lockGroup", "").trim().ifBlank { null },
                        allowExternalBrowser = obj.optBoolean("allowExternalBrowser", true),
                        desktopMode = obj.optBoolean("desktopMode", true),
                        pinned = obj.optBoolean("pinned", false)
                    )

                // مورد با چند لینک (مثلاً یک خواننده با ۳۶ آهنگ، یا چند فایل PDF): هر لینک تبدیل
                // می‌شود به یک کارتِ جدا در همان پوشه، دقیقاً مثل وقتی که جدا جدا اضافه شده بودند.
                // هر کارت بعداً بر اساس پسوند لینکش باز می‌شود (mp3 پخش، pdf نمایش، عکس، apk و...).
                // فیلم/سریال، تلویزیون و رادیو و بنرها مثل قبل از links خودشان استفاده می‌کنند.
                val splitTypes = setOf(ContentType.VIDEO, ContentType.TV, ContentType.RADIO, ContentType.BANNER, ContentType.HOME_BANNER)
                if (parsedItem.type !in splitTypes && !parsedItem.locked && parsedItem.links.isNotEmpty()) {
                    if (parsedItem.url.isNotBlank()) {
                        result.add(parsedItem.copy(links = emptyList()))
                    }
                    parsedItem.links.forEachIndexed { idx, link ->
                        result.add(
                            parsedItem.copy(
                                id = parsedItem.id + "__" + idx,
                                // عنوان = اسم خواننده (همان عنوان مورد)، زیرعنوان = اسم آهنگ
                                description = link.label.ifBlank { parsedItem.title + " " + (idx + 1) },
                                url = link.url,
                                links = emptyList()
                            )
                        )
                    }
                } else {
                    result.add(parsedItem)
                }
            }
            return result
        }
    }
}
