package com.kafinet.asannet

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.wifi.WifiManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ارسال لینک ویدیو به تلویزیون‌های هوشمند از طریق DLNA / UPnP (مثل Web Video Caster و AllCast).
 * تلویزیون خودش لینک را از اینترنت می‌گیرد؛ پس باید هم‌شبکه (همان Wi-Fi) و دارای اینترنت باشد.
 */
object DlnaCaster {

    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    data class Device(val name: String, val controlUrl: String)

    @Volatile
    private var lastDevice: Device? = null

    // ---------------------------------------------------------------- پنجره‌ی انتخاب تلویزیون

    fun showDevicePicker(activity: Activity, mediaUrl: String, title: String) {
        if (!mediaUrl.startsWith("http", ignoreCase = true)) {
            Toast.makeText(activity, "این فایل داخل برنامه است و قابل ارسال به تلویزیون نیست", Toast.LENGTH_LONG).show()
            return
        }
        val local = localIpv4()
        if (local == null) {
            AlertDialog.Builder(activity)
                .setTitle("اتصال به Wi-Fi")
                .setMessage("برای ارسال به تلویزیون، گوشی باید به همان Wi-Fi که تلویزیون به آن وصل است متصل باشد.")
                .setPositiveButton("باشه", null)
                .show()
            return
        }

        val builder = AlertDialog.Builder(activity)
        val ctx = builder.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val statusText = TextView(ctx).apply {
            text = "در حال جستجوی تلویزیون‌ها در شبکه…"
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val progress = ProgressBar(ctx).apply { isIndeterminate = true }
        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { addView(listBox) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            addView(statusText)
            addView(
                progress,
                LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(8)
                }
            )
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(8) }
            )
        }

        val cancelled = AtomicBoolean(false)
        val builderDialog = builder
            .setTitle("ارسال به تلویزیون")
            .setView(root)
            .setPositiveButton("جستجوی دوباره", null)
            .setNegativeButton("بستن", null)
        if (lastDevice != null) {
            builderDialog.setNeutralButton("توقف پخش روی تلویزیون", null)
        }
        val dialog = builderDialog.create()
        dialog.setOnDismissListener { cancelled.set(true) }
        dialog.show()

        val rowBackground = TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        val shownKeys = HashSet<String>()
        var scanId = 0

        fun sendTo(device: Device, direct: Boolean) {
                    cancelled.set(true)
                    statusText.text = "در حال ارسال به «" + device.name + "»…"
                    progress.visibility = View.VISIBLE
                    listBox.visibility = View.GONE
                    Thread {
                        var error: String? = null
                        try {
                            cast(device, mediaUrl, title, direct)
                        } catch (e: Exception) {
                            error = e.message ?: "خطای نامشخص"
                        }
                        activity.runOnUiThread {
                            try {
                                if (error == null) {
                                    dialog.dismiss()
                                    if (StreamRelay.isRunning()) {
                                        // پخش از طریق گوشی انجام می‌شود؛ سرویس پیش‌زمینه آن را زنده نگه می‌دارد
                                        CastRelayService.start(activity)
                                        Toast.makeText(
                                            activity,
                                            "پخش روی تلویزیون شروع شد. می‌توانید صفحه‌ی گوشی را خاموش کنید. برای توقف، از اعلان یا همین دکمه استفاده کنید.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        Toast.makeText(activity, "پخش روی تلویزیون شروع شد", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    progress.visibility = View.GONE
                                    listBox.visibility = View.VISIBLE
                                    statusText.text = "ارسال ناموفق بود. تلویزیون این لینک را نپذیرفت یا قابل پخش نیست.\n(" + (error ?: "") + ")"
                                }
                            } catch (ignored: Exception) {
                            }
                        }
                    }.start()
        }

        fun addDeviceRow(device: Device) {
            val key = device.controlUrl
            if (!shownKeys.add(key)) return
            val row = TextView(ctx).apply {
                text = "📺  " + device.name
                textSize = 17f
                setPadding(dp(8), dp(14), dp(8), dp(14))
                setBackgroundResource(rowBackground.resourceId)
                isClickable = true
                setOnClickListener { sendTo(device, false) }
            }
            listBox.addView(row)
        }

        fun startScan() {
            scanId += 1
            val myScan = scanId
            cancelled.set(false)
            shownKeys.clear()
            listBox.removeAllViews()
            listBox.visibility = View.VISIBLE
            statusText.text = "در حال جستجوی تلویزیون‌ها در شبکه…"
            progress.visibility = View.VISIBLE
            Thread {
                var found = 0
                discover(activity, local, cancelled) { device ->
                    found += 1
                    activity.runOnUiThread {
                        if (myScan == scanId && dialog.isShowing) {
                            addDeviceRow(device)
                            statusText.text = "یک تلویزیون را انتخاب کنید:"
                        }
                    }
                }
                activity.runOnUiThread {
                    if (myScan == scanId && dialog.isShowing && !cancelled.get()) {
                        progress.visibility = View.GONE
                        if (found == 0) {
                            statusText.text = "تلویزیونی پیدا نشد. مطمئن شوید تلویزیون روشن است و به همان Wi-Fi گوشی وصل است." +
                                (if (lastDiag.isNotEmpty()) "\n\n" + lastDiag else "")
                        }
                    }
                }
            }.start()
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            cancelled.set(true)
            startScan()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
        if (lastDevice != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                Thread {
                    try {
                        stop()
                    } catch (ignored: Exception) {
                    }
                    CastRelayService.stop(activity)
                    activity.runOnUiThread {
                        Toast.makeText(activity, "دستور توقف به تلویزیون ارسال شد", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        }
        startScan()
    }

    // ---------------------------------------------------------------- فرمان‌ها

    /**
     * لینک را به تلویزیون می‌فرستد و پخش را شروع می‌کند. باید خارج از نخ اصلی صدا زده شود.
     * لینک‌های زنده (HLS / .m3u8) اول از طریق «واسطه‌ی روی گوشی» فرستاده می‌شوند و اگر نشد، مستقیم؛
     * فایل‌های ساده (mp4 و ...) اول مستقیم و بعد از طریق واسطه.
     */
    fun cast(device: Device, mediaUrl: String, title: String, direct: Boolean = false) {
        val local = localIpv4()
        val ext = mediaUrl.lowercase().substringBefore('?').substringAfterLast('.', "")
        val progressive = ext in listOf("mp4", "m4v", "mkv", "avi", "ts", "mpg", "mpeg", "mp3", "aac")
        val order = when {
            direct || local == null -> listOf(false)
            progressive -> listOf(false, true)
            else -> listOf(true, false)
        }

        var lastError: Exception? = null
        var relayError: Exception? = null
        for (useRelay in order) {
            try {
                if (useRelay && local != null) {
                    val relayUrl = StreamRelay.start(mediaUrl, local)
                    try {
                        castDirect(device, relayUrl, title, listOf("video/mpeg", "video/mp2t"))
                    } catch (e: Exception) {
                        StreamRelay.stop()
                        throw e
                    }
                    // چند ثانیه صبر کن تا معلوم شود واسطه واقعاً توانسته جریان را بگیرد
                    val waitStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - waitStart < 8000) {
                        if (StreamRelay.bytesSent > 0) break
                        val relayProblem = StreamRelay.lastError
                        if (relayProblem != null) {
                            StreamRelay.stop()
                            throw IOException("واسطه‌ی گوشی نتوانست جریان را بگیرد: " + relayProblem)
                        }
                        Thread.sleep(300)
                    }
                } else {
                    castDirect(device, mediaUrl, title, listOf(mimeFor(mediaUrl)))
                }
                return
            } catch (e: Exception) {
                lastError = e
                if (useRelay) relayError = e
            }
        }
        throw relayError ?: lastError ?: IOException("ارسال ناموفق")
    }

    private fun castDirect(device: Device, mediaUrl: String, title: String, mimes: List<String>) {
        // تلویزیون‌های مختلف سلیقه‌ی متفاوتی دارند؛ چند حالت را به ترتیب امتحان می‌کنیم
        val urls = ArrayList<String>()
        urls.add(mediaUrl)
        if (mediaUrl.startsWith("https://", ignoreCase = true)) {
            urls.add("http://" + mediaUrl.substring(8))
        }
        val metaVariants = ArrayList<String>()
        for (mime in mimes) {
            metaVariants.add(didl(title, mediaUrl, "http-get:*:" + mime + ":*"))
            metaVariants.add(
                didl(
                    title, mediaUrl,
                    "http-get:*:" + mime + ":DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                )
            )
        }
        metaVariants.add("")

        var lastError: Exception? = null
        for (candidate in urls) {
            for (meta in metaVariants) {
                val metaForUrl = if (meta.isEmpty()) "" else meta.replace(esc(mediaUrl), esc(candidate))
                try {
                    try {
                        soap(device.controlUrl, "Stop", "<InstanceID>0</InstanceID>")
                    } catch (ignored: Exception) {
                    }
                    Thread.sleep(300)
                    soap(
                        device.controlUrl,
                        "SetAVTransportURI",
                        "<InstanceID>0</InstanceID><CurrentURI>" + esc(candidate) + "</CurrentURI>" +
                            "<CurrentURIMetaData>" + esc(metaForUrl) + "</CurrentURIMetaData>"
                    )
                    Thread.sleep(500)
                    soap(device.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                    lastDevice = device
                    return
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        throw lastError ?: IOException("ارسال ناموفق")
    }

    private fun didl(title: String, url: String, protocolInfo: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"false\">" +
            "<dc:title>" + esc(title) + "</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"" + protocolInfo + "\">" + esc(url) + "</res>" +
            "</item></DIDL-Lite>"

    /** پخش روی آخرین تلویزیونی که به آن ارسال شده را متوقف می‌کند. */
    fun stop() {
        try {
            val device = lastDevice
            if (device != null) {
                soap(device.controlUrl, "Stop", "<InstanceID>0</InstanceID>")
            }
        } finally {
            StreamRelay.stop()
        }
    }

    private fun soap(controlUrl: String, action: String, args: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:" + action + " xmlns:u=\"" + AVT + "\">" + args + "</u:" + action + ">" +
            "</s:Body></s:Envelope>"
        val c = URL(controlUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000
        c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"" + AVT + "#" + action + "\"")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = try {
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            ""
        }
        if (code !in 200..299) {
            val faultCode = Regex("<errorCode>(.*?)</errorCode>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            val faultText = Regex("<errorDescription>(.*?)</errorDescription>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            val detail = if (faultCode != null) "خطای تلویزیون " + faultCode + (if (faultText != null) " (" + faultText + ")" else "") else text.take(120)
            throw IOException(action + ": HTTP " + code + " — " + detail)
        }
        return text
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private fun unesc(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")

    private fun mimeFor(url: String): String {
        val u = url.lowercase().substringBefore('?')
        return when {
            u.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            u.endsWith(".ts") -> "video/mpeg"
            u.endsWith(".mkv") -> "video/x-matroska"
            u.endsWith(".mp4") || u.endsWith(".m4v") -> "video/mp4"
            else -> "video/mp4"
        }
    }

    // ---------------------------------------------------------------- پیدا کردن تلویزیون‌ها (SSDP)

    private fun localIpv4(): InetAddress? {
        var fallback: InetAddress? = null
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in ifaces.toList()) {
                try {
                    if (!nif.isUp || nif.isLoopback) continue
                } catch (e: Exception) {
                    continue
                }
                val name = nif.name.lowercase()
                for (addr in nif.inetAddresses.toList()) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) return addr
                        val mobile = name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-")
                        if (!mobile && addr.isSiteLocalAddress && fallback == null) fallback = addr
                    }
                }
            }
        } catch (e: Exception) {
        }
        return fallback
    }

    @Volatile
    private var lastDiag: String = ""

    @Volatile
    private var lastFetchError: String? = null

    private fun discover(
        context: Context,
        local: InetAddress,
        cancelled: AtomicBoolean,
        onDevice: (Device) -> Unit
    ) {
        var lock: WifiManager.MulticastLock? = null
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("asannet-dlna")
            lock?.setReferenceCounted(false)
            lock?.acquire()
        } catch (e: Exception) {
        }

        val seenLocations = HashSet<String>()
        val seenDevices = HashSet<String>()
        var socketHolder: MulticastSocket? = null
        var scanError: String? = null
        val dbg = ArrayList<String>()
        lastDiag = ""
        try {
            val socket = MulticastSocket(null)
            socketHolder = socket
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(local, 0))
            try {
                socket.setInterface(local)
            } catch (e: Exception) {
            }
            socket.timeToLive = 4
            socket.soTimeout = 1000

            val group = InetAddress.getByName("239.255.255.250")
            val searchTargets = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:device:MediaRenderer:2",
                "urn:schemas-upnp-org:service:AVTransport:2",
                "upnp:rootdevice",
                "ssdp:all"
            )
            val end = System.currentTimeMillis() + 10000
            var nextSend = 0L
            val buffer = ByteArray(4096)
            while (!cancelled.get() && System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (st in searchTargets) {
                        val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: " + st + "\r\n\r\n").toByteArray()
                        socket.send(DatagramPacket(msg, msg.size, group, 1900))
                    }
                    nextSend = System.currentTimeMillis() + 2500
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val locationLine = text.lines().firstOrNull { it.startsWith("location:", ignoreCase = true) }
                    if (locationLine == null) continue
                    val location = locationLine.substring(9).trim()
                    if (!seenLocations.add(location)) continue
                    val device = fetchDevice(location)
                    if (dbg.size < 6) {
                        dbg.add(location.removePrefix("http://") + " ← " + (if (device != null) "OK" else (lastFetchError ?: "؟")))
                    }
                    if (device != null && seenDevices.add(device.controlUrl)) {
                        onDevice(device)
                    }
                } catch (e: SocketTimeoutException) {
                }
            }
        } catch (e: Exception) {
            scanError = e.message ?: e.javaClass.simpleName
        } finally {
            lastDiag = when {
                scanError != null -> "خطا در جستجو: " + scanError
                seenLocations.isEmpty() -> "هیچ پاسخی از شبکه نیامد. احتمالاً روتر ارتباط بین دستگاه‌ها را مسدود کرده یا گوشی و تلویزیون در یک شبکه نیستند."
                seenDevices.isEmpty() -> "از " + seenLocations.size + " دستگاه پاسخ آمد ولی هیچ‌کدام قابلیت دریافت ویدیو (DLNA) نداشتند. ممکن است تلویزیون فقط Google Cast پشتیبانی کند.\n\n" + dbg.joinToString("\n")
                else -> ""
            }
            try {
                socketHolder?.close()
            } catch (e: Exception) {
            }
            try {
                lock?.release()
            } catch (e: Exception) {
            }
        }
    }

    /** فایل توضیحات دستگاه را می‌خواند و اگر قابلیت AVTransport داشت (یعنی می‌تواند ویدیو پخش کند) برمی‌گرداند. */
    private fun fetchDevice(location: String): Device? {
        lastFetchError = null
        try {
            val c = URL(location).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            val xml = c.inputStream.bufferedReader().use { it.readText() }

            val serviceRegex = Regex("<service>(.*?)</service>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            var control: String? = null
            for (m in serviceRegex.findAll(xml)) {
                val block = m.groupValues[1]
                if (block.contains("AVTransport", ignoreCase = true)) {
                    control = Regex("<controlURL>(.*?)</controlURL>", RegexOption.IGNORE_CASE)
                        .find(block)?.groupValues?.get(1)?.trim()
                    break
                }
            }
            val controlPath: String = control ?: run {
                lastFetchError = "AVTransport ندارد"
                return null
            }
            if (controlPath.isBlank()) return null

            val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.IGNORE_CASE)
                .find(xml)?.groupValues?.get(1)?.trim()
            val base = Regex("<URLBase>(.*?)</URLBase>", RegexOption.IGNORE_CASE)
                .find(xml)?.groupValues?.get(1)?.trim()
            val baseUrl = URL(if (!base.isNullOrBlank()) base else location)
            return Device(unesc(name ?: "تلویزیون"), URL(baseUrl, controlPath).toString())
        } catch (e: Exception) {
            lastFetchError = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(90)
            return null
        }
    }
}
