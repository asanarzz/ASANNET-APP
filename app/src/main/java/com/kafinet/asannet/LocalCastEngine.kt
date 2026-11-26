package com.kafinet.asannet

import android.content.Context
import android.net.wifi.WifiManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ارسال «فایل‌های داخل گوشی» (عکس، ویدیو، موزیک) به تلویزیون از طریق DLNA.
 *
 * این کلاس کاملاً مستقل از DlnaCaster / StreamRelay / CastRelayService است (که مخصوص پخش زنده‌ی
 * شبکه‌های تلویزیونی و رادیو هستند) و هیچ‌کدام از آن‌ها را تغییر نمی‌دهد یا متوقف نمی‌کند.
 * سرور HTTP خودش روی پورت جداگانه کار می‌کند و فقط فایل‌های انتخاب‌شده توسط کاربر را (با پشتیبانی از
 * پرش جلو/عقب) در اختیار تلویزیون می‌گذارد.
 */
object LocalCastEngine {

    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    data class Device(val name: String, val controlUrl: String)

    /** تلویزیونی که کاربر در این صفحه انتخاب کرده (تا بسته شدن برنامه). */
    @Volatile
    var selected: Device? = null

    @Volatile
    var lastDiag: String = ""

    @Volatile
    private var lastFetchError: String? = null

    private val files = ConcurrentHashMap<String, File>()
    private val counter = AtomicInteger(0)

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val clients = Collections.synchronizedList(ArrayList<Socket>())

    @Volatile
    private var lastActive = System.currentTimeMillis()

    fun isRunning(): Boolean = serverSocket != null

    /** چند میلی‌ثانیه است که هیچ تلویزیونی به سرور فایل وصل نیست (۰ اگر الان وصل است). */
    fun idleMillis(): Long = if (clients.isEmpty()) System.currentTimeMillis() - lastActive else 0L

    // ---------------------------------------------------------------- ارسال

    /** فایل را به تلویزیون می‌فرستد و پخش را شروع می‌کند. باید خارج از نخ اصلی صدا زده شود. */
    fun castFile(device: Device, file: File, title: String) {
        val local = localIpv4() ?: throw IOException("گوشی به Wi-Fi وصل نیست")
        val server = ensureServer(local)
        val key = counter.incrementAndGet().toString()
        files[key] = file
        val kind = FileOps.kindOf(file)
        val ext = file.extension.lowercase().ifEmpty { "bin" }
        val url = "http://" + local.hostAddress + ":" + server.localPort + "/f/" + key + "/media." + ext
        val mime = mimeFor(file, kind)

        val upnpClass = when (kind) {
            FileKind.AUDIO -> "object.item.audioItem.musicTrack"
            FileKind.IMAGE -> "object.item.imageItem.photo"
            else -> "object.item.videoItem"
        }
        val dlnaOp = if (kind == FileKind.IMAGE) "DLNA.ORG_OP=00" else "DLNA.ORG_OP=01"
        val metaVariants = listOf(
            didl(title, url, upnpClass, "http-get:*:$mime:$dlnaOp;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"),
            didl(title, url, upnpClass, "http-get:*:$mime:*"),
            ""
        )

        var lastError: Exception? = null
        for (meta in metaVariants) {
            try {
                try {
                    soap(device.controlUrl, "Stop", "<InstanceID>0</InstanceID>")
                } catch (ignored: Exception) {
                }
                Thread.sleep(300)
                soap(
                    device.controlUrl, "SetAVTransportURI",
                    "<InstanceID>0</InstanceID><CurrentURI>" + esc(url) + "</CurrentURI>" +
                        "<CurrentURIMetaData>" + esc(meta) + "</CurrentURIMetaData>"
                )
                Thread.sleep(500)
                soap(device.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("ارسال ناموفق")
    }

    /** دستور توقف را فقط وقتی کاربر خودش دکمه‌ی توقف را بزند به تلویزیون می‌فرستد، و سرور فایل را می‌بندد. */
    fun stopByUser() {
        try {
            val d = selected
            if (d != null) soap(d.controlUrl, "Stop", "<InstanceID>0</InstanceID>")
        } catch (ignored: Exception) {
        }
        stopServer()
    }

    private fun mimeFor(file: File, kind: FileKind): String {
        val m = FileOps.mimeOf(file)
        if (m != "*/*") return m
        return when (kind) {
            FileKind.AUDIO -> "audio/mpeg"
            FileKind.IMAGE -> "image/jpeg"
            else -> "video/mp4"
        }
    }

    private fun didl(title: String, url: String, upnpClass: String, protocolInfo: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"false\">" +
            "<dc:title>" + esc(title) + "</dc:title>" +
            "<upnp:class>" + upnpClass + "</upnp:class>" +
            "<res protocolInfo=\"" + protocolInfo + "\">" + esc(url) + "</res>" +
            "</item></DIDL-Lite>"

    // ---------------------------------------------------------------- سرور فایل

    @Synchronized
    private fun ensureServer(local: InetAddress): ServerSocket {
        val cur = serverSocket
        if (cur != null && !cur.isClosed && cur.inetAddress == local) return cur
        closeServerOnly()
        val s = ServerSocket(0, 16, local)
        serverSocket = s
        lastActive = System.currentTimeMillis()
        Thread {
            try {
                while (!s.isClosed) {
                    val c = s.accept()
                    clients.add(c)
                    Thread { handleClient(c) }.start()
                }
            } catch (e: Exception) {
            }
        }.start()
        return s
    }

    @Synchronized
    private fun closeServerOnly() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        serverSocket = null
        synchronized(clients) {
            for (c in clients) {
                try {
                    c.close()
                } catch (e: Exception) {
                }
            }
            clients.clear()
        }
    }

    /** سرور فایل را می‌بندد (به تلویزیون دستوری نمی‌فرستد). */
    @Synchronized
    fun stopServer() {
        closeServerOnly()
        files.clear()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val out = client.getOutputStream()

            val requestLine = readLine(input) ?: return
            var range: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("range:", ignoreCase = true)) range = line.substring(6).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts[0].uppercase()
            val path = parts.getOrNull(1).orEmpty()
            val key = path.removePrefix("/f/").substringBefore('/')
            val file = files[key]
            if (file == null || !file.isFile) {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val len = file.length()
            var start = 0L
            var end = len - 1
            var partial = false
            if (range != null && range.startsWith("bytes=", ignoreCase = true)) {
                val spec = range.substring(6).substringBefore(',')
                val a = spec.substringBefore('-').trim()
                val b = spec.substringAfter('-', "").trim()
                if (a.isEmpty()) {
                    val n = b.toLongOrNull() ?: 0L
                    start = maxOf(0L, len - n)
                } else {
                    start = a.toLongOrNull() ?: 0L
                    if (b.isNotEmpty()) end = minOf(end, b.toLongOrNull() ?: end)
                }
                if (start > end || start >= len) {
                    out.write(
                        ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + len +
                            "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").toByteArray()
                    )
                    out.flush()
                    return
                }
                partial = true
            }

            val kind = FileOps.kindOf(file)
            val mime = mimeFor(file, kind)
            val op = if (kind == FileKind.IMAGE) "00" else "01"
            val transfer = if (kind == FileKind.IMAGE) "Interactive" else "Streaming"
            val sb = StringBuilder()
            sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: ").append(mime).append("\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Content-Length: ").append(end - start + 1).append("\r\n")
            if (partial) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(len).append("\r\n")
            sb.append("transferMode.dlna.org: ").append(transfer).append("\r\n")
            sb.append("contentFeatures.dlna.org: DLNA.ORG_OP=").append(op)
                .append(";DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()
            if (method == "HEAD") return

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                var remaining = end - start + 1
                val buf = ByteArray(64 * 1024)
                while (remaining > 0 && !client.isClosed) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            out.flush()
        } catch (e: Exception) {
            // تلویزیون اتصال را بسته (مثلاً هنگام پرش)؛ مشکلی نیست
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
            }
            clients.remove(client)
            lastActive = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------- فرمان‌های DLNA

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

    // ---------------------------------------------------------------- شبکه و پیدا کردن تلویزیون‌ها (SSDP)

    fun localIpv4(): InetAddress? {
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

    fun discover(
        context: Context,
        local: InetAddress,
        cancelled: AtomicBoolean,
        onDevice: (Device) -> Unit
    ) {
        var lock: WifiManager.MulticastLock? = null
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("asannet-localcast")
            lock?.setReferenceCounted(false)
            lock?.acquire()
        } catch (e: Exception) {
        }

        val seenLocations = HashSet<String>()
        val seenDevices = HashSet<String>()
        var socketHolder: MulticastSocket? = null
        var scanError: String? = null
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
                    val locationLine = text.lines().firstOrNull { it.startsWith("location:", ignoreCase = true) } ?: continue
                    val location = locationLine.substring(9).trim()
                    if (!seenLocations.add(location)) continue
                    val device = fetchDevice(location)
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
                scanError != null -> "خطا در جستجو: $scanError"
                seenLocations.isEmpty() -> "هیچ پاسخی از شبکه نیامد. احتمالاً روتر ارتباط بین دستگاه‌ها را مسدود کرده یا گوشی و تلویزیون در یک شبکه نیستند."
                seenDevices.isEmpty() -> "از ${seenLocations.size} دستگاه پاسخ آمد ولی هیچ‌کدام قابلیت دریافت رسانه (DLNA) نداشتند."
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
