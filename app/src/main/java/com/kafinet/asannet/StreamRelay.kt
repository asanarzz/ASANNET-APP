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

    private const val V_PID = 0x100
    private const val A_PID = 0x101
    private const val PMT_PID = 0x1000
    private val AUDIO_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87)

    @Volatile
    private var patCc = 0

    @Volatile
    private var pmtCc = 0

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
        patCc = 0
        pmtCc = 0
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
        var audioUrl: String? = null

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
                audioUrl = try {
                    audioPlaylistFor(lines, base, current)
                } catch (e: IOException) {
                    null
                }
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
            var audioSegs: List<Pair<Long, String>> = emptyList()
            if (audioUrl != null) audioSegs = loadAudioSegments(audioUrl)

            for ((i, segmentUrl) in segments.withIndex()) {
                if (!alive()) return
                val seq = mediaSeq + i
                if (seq <= lastSeq) continue
                lastSeq = seq
                // در شروع پخش زنده، فقط ۳ قطعه‌ی آخر را بفرست تا به لحظه‌ی زنده نزدیک باشی
                if (firstRound && live && i < segments.size - 3) continue
                try {
                    if (audioUrl != null) {
                        // صدا جدا تعریف شده: قطعه‌ی تصویر و صدا را بگیر و در یک جریان ترکیب کن
                        val v = fetchBytes(segmentUrl)
                        val fromEnd = segments.size - i
                        var aSeg = findAudio(audioSegs, seq, mediaSeq, segments.size, fromEnd)
                        if (aSeg == null) {
                            Thread.sleep(700)
                            audioSegs = loadAudioSegments(audioUrl)
                            aSeg = findAudio(audioSegs, seq, mediaSeq, segments.size, fromEnd)
                        }
                        var data = v
                        if (aSeg != null) {
                            try {
                                val m = muxTs(v, fetchBytes(aSeg))
                                if (m != null) data = m
                            } catch (e: IOException) {
                                // اگر صدا گرفته نشد، فقط تصویر ارسال می‌شود
                            }
                        }
                        writeAll(data, out)
                    } else {
                        val c = openWithRedirects(segmentUrl)
                        c.inputStream.use { copy(it, out, alive) }
                        c.disconnect()
                    }
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


    // ---------- صدای جدا (EXT-X-MEDIA) و ترکیب آن با تصویر ----------

    private class EsInfo(val pid: Int, val type: Int, val info: ByteArray)

    private fun attr(line: String, key: String): String? {
        val m = Regex("(?:^|[:,])" + key + "=(\"([^\"]*)\"|[^,]*)").find(line) ?: return null
        val quoted = m.groupValues[2]
        return if (quoted.isNotEmpty()) quoted else m.groupValues[1]
    }

    private fun audioPlaylistFor(lines: List<String>, base: String, variantUrl: String): String? {
        var group: String? = null
        for ((i, l) in lines.withIndex()) {
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            var j = i + 1
            while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
            if (j < lines.size && resolve(base, lines[j]) == variantUrl) {
                group = attr(l, "AUDIO")
                break
            }
        }
        if (group == null) return null
        var chosen: String? = null
        for (l in lines) {
            if (!l.startsWith("#EXT-X-MEDIA")) continue
            if (attr(l, "TYPE") != "AUDIO" || attr(l, "GROUP-ID") != group) continue
            val uri = attr(l, "URI") ?: continue
            if (chosen == null || attr(l, "DEFAULT") == "YES") chosen = resolve(base, uri)
        }
        return chosen
    }

    private fun loadAudioSegments(url: String): List<Pair<Long, String>> {
        try {
            val c = openWithRedirects(url)
            val aBase = c.url.toString()
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val result = ArrayList<Pair<Long, String>>()
            var seq = 0L
            var idx = 0L
            for (raw in text.split('\n')) {
                val l = raw.trim()
                if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                } else if (l.isNotEmpty() && !l.startsWith("#") && !l.startsWith("EXT-X-")) {
                    result.add(Pair(seq + idx, resolve(aBase, l)))
                    idx += 1
                }
            }
            return result
        } catch (e: IOException) {
            return emptyList()
        }
    }

    private fun findAudio(
        list: List<Pair<Long, String>>,
        seq: Long,
        mediaSeq: Long,
        count: Int,
        fromEnd: Int
    ): String? {
        val exact = list.firstOrNull { it.first == seq }
        if (exact != null) return exact.second
        val overlap = list.any { it.first >= mediaSeq && it.first < mediaSeq + count }
        return if (overlap) null else list.getOrNull(list.size - fromEnd)?.second
    }

    private fun fetchBytes(url: String): ByteArray {
        val c = openWithRedirects(url)
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun writeAll(data: ByteArray, out: OutputStream) {
        try {
            out.write(data)
            out.flush()
            bytesSent += data.size
        } catch (e: IOException) {
            throw ClientClosed()
        }
    }

    private fun tsStart(ts: ByteArray): Int {
        var i = 0
        while (i < 188 && i + 188 < ts.size) {
            if (ts[i] == 0x47.toByte() && ts[i + 188] == 0x47.toByte()) return i
            i += 1
        }
        return -1
    }

    private fun pidOf(ts: ByteArray, p: Int): Int =
        ((ts[p + 1].toInt() and 0x1F) shl 8) or (ts[p + 2].toInt() and 0xFF)

    private fun sectionStart(ts: ByteArray, p: Int): Int {
        if ((ts[p + 1].toInt() and 0x40) == 0) return -1
        val afc = (ts[p + 3].toInt() shr 4) and 0x3
        if (afc == 0 || afc == 2) return -1
        val po = if (afc == 3) p + 5 + (ts[p + 4].toInt() and 0xFF) else p + 4
        if (po >= p + 188) return -1
        return po + 1 + (ts[po].toInt() and 0xFF)
    }

    private fun readEs(ts: ByteArray, start: Int): List<EsInfo> {
        var pmtPid = -1
        var p = start
        while (p + 188 <= ts.size && pmtPid < 0) {
            if (pidOf(ts, p) == 0) {
                val s = sectionStart(ts, p)
                if (s > 0 && s + 12 < p + 188 && (ts[s].toInt() and 0xFF) == 0x00) {
                    val len = ((ts[s + 1].toInt() and 0x0F) shl 8) or (ts[s + 2].toInt() and 0xFF)
                    val end = minOf(s + 3 + len - 4, p + 188)
                    var q = s + 8
                    while (q + 4 <= end) {
                        val prog = ((ts[q].toInt() and 0xFF) shl 8) or (ts[q + 1].toInt() and 0xFF)
                        val pp = ((ts[q + 2].toInt() and 0x1F) shl 8) or (ts[q + 3].toInt() and 0xFF)
                        if (prog != 0) {
                            pmtPid = pp
                            break
                        }
                        q += 4
                    }
                }
            }
            p += 188
        }
        val result = ArrayList<EsInfo>()
        if (pmtPid < 0) return result
        p = start
        while (p + 188 <= ts.size) {
            if (pidOf(ts, p) == pmtPid) {
                val s = sectionStart(ts, p)
                if (s > 0 && s + 12 < p + 188 && (ts[s].toInt() and 0xFF) == 0x02) {
                    val len = ((ts[s + 1].toInt() and 0x0F) shl 8) or (ts[s + 2].toInt() and 0xFF)
                    val progInfo = ((ts[s + 10].toInt() and 0x0F) shl 8) or (ts[s + 11].toInt() and 0xFF)
                    val end = minOf(s + 3 + len - 4, p + 188)
                    var q = s + 12 + progInfo
                    while (q + 5 <= end) {
                        val type = ts[q].toInt() and 0xFF
                        val epid = ((ts[q + 1].toInt() and 0x1F) shl 8) or (ts[q + 2].toInt() and 0xFF)
                        val il = ((ts[q + 3].toInt() and 0x0F) shl 8) or (ts[q + 4].toInt() and 0xFF)
                        val infoEnd = minOf(q + 5 + il, end)
                        result.add(EsInfo(epid, type, ts.copyOfRange(q + 5, infoEnd)))
                        q += 5 + il
                    }
                    return result
                }
            }
            p += 188
        }
        return result
    }

    private fun crc32(data: ByteArray, len: Int): Int {
        var crc = -1
        for (k in 0 until len) {
            crc = crc xor ((data[k].toInt() and 0xFF) shl 24)
            for (b in 0 until 8) {
                crc = if ((crc and Int.MIN_VALUE) != 0) (crc shl 1) xor 0x04C11DB7 else (crc shl 1)
            }
        }
        return crc
    }

    private fun putCrc(section: ByteArray) {
        val n = section.size - 4
        val crc = crc32(section, n)
        section[n] = (crc ushr 24).toByte()
        section[n + 1] = (crc ushr 16).toByte()
        section[n + 2] = (crc ushr 8).toByte()
        section[n + 3] = crc.toByte()
    }

    private fun psiPacket(pid: Int, cc: Int, section: ByteArray): ByteArray {
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or (cc and 0x0F)).toByte()
        pkt[4] = 0
        System.arraycopy(section, 0, pkt, 5, section.size)
        return pkt
    }

    private fun buildPat(): ByteArray {
        val s = ByteArray(16)
        s[0] = 0x00
        s[1] = 0xB0.toByte()
        s[2] = 0x0D
        s[3] = 0x00
        s[4] = 0x01
        s[5] = 0xC1.toByte()
        s[6] = 0x00
        s[7] = 0x00
        s[8] = 0x00
        s[9] = 0x01
        s[10] = (0xE0 or (PMT_PID shr 8)).toByte()
        s[11] = (PMT_PID and 0xFF).toByte()
        putCrc(s)
        return s
    }

    private fun buildPmt(video: EsInfo, audio: EsInfo): ByteArray {
        val vi = if (video.info.size <= 30) video.info else ByteArray(0)
        val ai = if (audio.info.size <= 30) audio.info else ByteArray(0)
        val len = 9 + (5 + vi.size) + (5 + ai.size) + 4
        val s = ByteArray(3 + len)
        s[0] = 0x02
        s[1] = (0xB0 or (len shr 8)).toByte()
        s[2] = (len and 0xFF).toByte()
        s[3] = 0x00
        s[4] = 0x01
        s[5] = 0xC1.toByte()
        s[6] = 0x00
        s[7] = 0x00
        s[8] = (0xE0 or (V_PID shr 8)).toByte()
        s[9] = (V_PID and 0xFF).toByte()
        s[10] = 0xF0.toByte()
        s[11] = 0x00
        var q = 12
        s[q] = video.type.toByte()
        s[q + 1] = (0xE0 or (V_PID shr 8)).toByte()
        s[q + 2] = (V_PID and 0xFF).toByte()
        s[q + 3] = (0xF0 or (vi.size shr 8)).toByte()
        s[q + 4] = (vi.size and 0xFF).toByte()
        System.arraycopy(vi, 0, s, q + 5, vi.size)
        q += 5 + vi.size
        s[q] = audio.type.toByte()
        s[q + 1] = (0xE0 or (A_PID shr 8)).toByte()
        s[q + 2] = (A_PID and 0xFF).toByte()
        s[q + 3] = (0xF0 or (ai.size shr 8)).toByte()
        s[q + 4] = (ai.size and 0xFF).toByte()
        System.arraycopy(ai, 0, s, q + 5, ai.size)
        putCrc(s)
        return s
    }

    /**
     * قطعه‌ی تصویر (TS) و قطعه‌ی صدای جدا (TS) را به یک قطعه‌ی TS با هر دو جریان تبدیل می‌کند.
     * اگر قطعه‌ها قابل خواندن نباشند، null برمی‌گرداند (و فقط تصویر فرستاده می‌شود).
     */
    private fun muxTs(video: ByteArray, audio: ByteArray): ByteArray? {
        val vs = tsStart(video)
        val aus = tsStart(audio)
        if (vs < 0 || aus < 0) return null
        val vEs = readEs(video, vs)
        val aEs = readEs(audio, aus)
        val v = vEs.firstOrNull { it.type == 0x1B || it.type == 0x24 || it.type == 0x02 || it.type == 0x01 } ?: return null
        if (vEs.any { it.type in AUDIO_TYPES }) return null
        val a = aEs.firstOrNull { it.type in AUDIO_TYPES } ?: aEs.firstOrNull { it.type == 0x06 } ?: return null

        val vOff = ArrayList<Int>()
        var p = vs
        while (p + 188 <= video.size) {
            if (pidOf(video, p) == v.pid) vOff.add(p)
            p += 188
        }
        val aOff = ArrayList<Int>()
        p = aus
        while (p + 188 <= audio.size) {
            if (pidOf(audio, p) == a.pid) aOff.add(p)
            p += 188
        }
        if (vOff.isEmpty() || aOff.isEmpty()) return null

        val out = java.io.ByteArrayOutputStream(video.size + audio.size + 1024)
        out.write(psiPacket(0, patCc, buildPat()))
        patCc = (patCc + 1) and 0x0F
        out.write(psiPacket(PMT_PID, pmtCc, buildPmt(v, a)))
        pmtCc = (pmtCc + 1) and 0x0F

        val n = vOff.size
        val m = aOff.size
        var vi = 0
        var ai = 0
        while (vi < n || ai < m) {
            val takeVideo = if (vi >= n) false else if (ai >= m) true else (vi.toLong() * m <= ai.toLong() * n)
            val src: ByteArray
            val off: Int
            val newPid: Int
            if (takeVideo) {
                src = video
                off = vOff[vi]
                newPid = V_PID
                vi += 1
            } else {
                src = audio
                off = aOff[ai]
                newPid = A_PID
                ai += 1
            }
            val pk = src.copyOfRange(off, off + 188)
            pk[1] = ((pk[1].toInt() and 0xE0) or (newPid shr 8)).toByte()
            pk[2] = (newPid and 0xFF).toByte()
            out.write(pk)
        }
        return out.toByteArray()
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
