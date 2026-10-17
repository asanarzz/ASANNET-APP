package com.kafinet.asannet

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * واسطه‌ی پخش روی گوشی (برای تلویزیون‌هایی که لینک‌های زنده‌ی HLS/.m3u8 یا https را مستقیم نمی‌پذیرند).
 *
 * گوشی لینک اصلی را از اینترنت می‌گیرد و روی شبکه‌ی Wi-Fi خانه یک جریان ساده‌ی MPEG-TS
 * (http://آی‌پی-گوشی:پورت/live.ts) به تلویزیون می‌دهد. تلویزیون فقط همین لینک ساده را باز می‌کند.
 *
 * محدودیت‌ها: فقط HLS با قطعه‌های TS و بدون رمزگذاری را پشتیبانی می‌کند.
 * هیچ تبدیل ویدیویی انجام نمی‌شود؛ پس کدک تصویر باید قابل پخش روی تلویزیون باشد.
 */
object StreamRelay {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private class ClientClosed : IOException("client closed")

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** آخرین خطای واسطه و تعداد بایت‌های ارسال‌شده به تلویزیون (برای تشخیص مشکل). */
    @Volatile
    var lastError: String? = null

    @Volatile
    var bytesSent: Long = 0

    @Volatile
    private var lastActive = System.currentTimeMillis()
    private val clients = java.util.Collections.synchronizedList(ArrayList<Socket>())

    fun isRunning(): Boolean = serverSocket != null

    /** چند میلی‌ثانیه است که هیچ تلویزیونی به واسطه وصل نیست (۰ اگر الان وصل است). */
    fun idleMillis(): Long = if (clients.isEmpty()) System.currentTimeMillis() - lastActive else 0L

    /** واسطه را روشن می‌کند و آدرس محلی برای تلویزیون را برمی‌گرداند. */
    @Synchronized
    fun start(sourceUrl: String, local: InetAddress): String {
        stop()
        lastError = null
        bytesSent = 0
        val server = ServerSocket(0, 8, local)
        serverSocket = server
        lastActive = System.currentTimeMillis()
        Thread {
            try {
                while (!server.isClosed) {
                    val client = server.accept()
                    clients.add(client)
                    Thread { handleClient(client, sourceUrl) }.start()
                }
            } catch (e: Exception) {
            }
        }.start()
        return "http://" + local.hostAddress + ":" + server.localPort + "/live.ts"
    }

    @Synchronized
    fun stop() {
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

    // ------------------------------------------------------------------ سرور ساده‌ی HTTP

    private fun handleClient(client: Socket, sourceUrl: String) {
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val out = client.getOutputStream()

            val requestLine = readLine(input) ?: return
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            val method = requestLine.substringBefore(' ').uppercase()

            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: video/mpeg\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                "\r\n"
            out.write(header.toByteArray())
            out.flush()
            if (method == "HEAD") return

            stream(sourceUrl, out) { !client.isClosed }
        } catch (e: Exception) {
            if (e !is ClientClosed) {
                lastError = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(100)
            }
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
            }
            clients.remove(client)
            lastActive = System.currentTimeMillis()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ دریافت و ارسال جریان

    private fun stream(sourceUrl: String, out: OutputStream, alive: () -> Boolean) {
        var current = sourceUrl
        var firstRound = true
        var lastSeq = -1L
        var failures = 0
        var variantHops = 0

        while (alive()) {
            var text: String? = null
            var base = current
            try {
                val conn = openWithRedirects(current)
                base = conn.url.toString()
                val bis = BufferedInputStream(conn.inputStream, 64 * 1024)
                bis.mark(16)
                val head = ByteArray(7)
                var got = 0
                while (got < head.size) {
                    val n = bis.read(head, got, head.size - got)
                    if (n < 0) break
                    got += n
                }
                bis.reset()
                if (got == 7 && String(head) == "#EXTM3U") {
                    text = bis.bufferedReader().readText()
                    conn.disconnect()
                } else {
                    // لینک مستقیم (مثلاً .ts یا .mp4): همان‌طور که هست پخش می‌شود
                    copy(bis, out, alive)
                    conn.disconnect()
                    return
                }
            } catch (e: ClientClosed) {
                throw e
            } catch (e: IOException) {
                failures += 1
                if (failures > 8) throw e
                Thread.sleep(2000)
                continue
            }

            val body: String = text ?: throw IOException("empty playlist")
            val lines = body.split('\n').map { it.trim() }

            // فهرست اصلی (master): یکی از کیفیت‌ها را انتخاب کن
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                variantHops += 1
                if (variantHops > 5) throw IOException("playlist loop")
                current = pickVariant(lines, base)
                continue
            }
            if (lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) {
                throw IOException("encrypted stream")
            }
            if (lines.any { it.startsWith("#EXT-X-MAP") }) {
                throw IOException("fmp4 stream")
            }

            var mediaSeq = 0L
            var target = 6
            var endList = false
            val segments = ArrayList<String>()
            for (l in lines) {
                when {
                    l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> mediaSeq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                    l.startsWith("#EXT-X-TARGETDURATION:") -> target = l.substringAfter(':').trim().toIntOrNull() ?: 6
                    l.startsWith("#EXT-X-ENDLIST") -> endList = true
                    l.isNotEmpty() && !l.startsWith("#") && !l.startsWith("EXT-X-") -> segments.add(resolve(base, l))
                }
            }
            val live = !endList

            for ((i, segmentUrl) in segments.withIndex()) {
                if (!alive()) return
                val seq = mediaSeq + i
                if (seq <= lastSeq) continue
                lastSeq = seq
                // در شروع پخش زنده، فقط ۳ قطعه‌ی آخر را بفرست تا به لحظه‌ی زنده نزدیک باشی
                if (firstRound && live && i < segments.size - 3) continue
                try {
                    val c = openWithRedirects(segmentUrl)
                    c.inputStream.use { copy(it, out, alive) }
                    c.disconnect()
                    failures = 0
                } catch (e: ClientClosed) {
                    throw e
                } catch (e: IOException) {
                    failures += 1
                    if (failures > 8) throw e
                }
            }
            firstRound = false
            if (endList) return
            Thread.sleep(maxOf(1000L, target * 500L))
        }
    }

    private fun copy(input: InputStream, out: OutputStream, alive: () -> Boolean) {
        val buffer = ByteArray(32 * 1024)
        while (alive()) {
            val n = input.read(buffer)
            if (n < 0) break
            try {
                out.write(buffer, 0, n)
                bytesSent += n
            } catch (e: IOException) {
                throw ClientClosed()
            }
        }
        try {
            out.flush()
        } catch (e: IOException) {
            throw ClientClosed()
        }
    }

    private fun pickVariant(lines: List<String>, base: String): String {
        var bestUri: String? = null
        var bestBw = -1L
        var lowestUri: String? = null
        var lowestBw = Long.MAX_VALUE
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j]
                    if (bw <= 4_500_000L && bw > bestBw) {
                        bestBw = bw
                        bestUri = uri
                    }
                    if (bw < lowestBw) {
                        lowestBw = bw
                        lowestUri = uri
                    }
                }
                i = j
            }
            i += 1
        }
        val chosen = bestUri ?: lowestUri ?: throw IOException("no variant")
        return resolve(base, chosen)
    }

    private fun resolve(base: String, ref: String): String = URL(URL(base), ref).toString()

    private fun openWithRedirects(startUrl: String): HttpURLConnection {
        var current = startUrl
        for (attempt in 0 until 6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location == null) throw IOException("redirect without location")
                current = resolve(current, location)
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP " + code)
            }
            return c
        }
        throw IOException("too many redirects")
    }
}
