package com.airmouse.gyro

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 音频通道：手机麦克风送进电脑 / 电脑声音从手机放出来。
 *
 * 走的是单独的一条 TCP 连接（默认 47778），和鼠标那条完全分开——
 * 音频是连续二进制流，混进「一行一条 JSON」的链路会把解析搞乱；
 * 分开之后开麦、听声音都不影响鼠标和手柄，掉线也各断各的。
 *
 * 帧格式（两个方向都一样）：4 字节小端长度 + 载荷，载荷是 PCM16。
 * 握手是一行 JSON：{"t":"au","dir":"mic","rate":48000,"ch":1}，电脑端回 {"t":"a-ok",...}。
 */
object AudioLink {

    @Volatile var host: String = "127.0.0.1"
    @Volatile var port: Int = 47778
    /** 给界面报状态用的，回调在后台线程 */
    @Volatile var onState: ((what: String, text: String) -> Unit)? = null
    /** 麦克风优先：开麦时把外放关掉（最彻底的回声消除，用户可在设置里关掉） */
    @Volatile var micPriority: Boolean = true

    private const val RATE = 48000
    private const val FRAME_SAMPLES = RATE / 50        // 20ms
    private const val FRAME_BYTES = FRAME_SAMPLES * 2  // 单声道 PCM16

    /** 开麦时电脑声音压到这个比例（约 -9 dB），切断声学回授 */
    private const val DUCK_VOLUME = 0.35f

    @Volatile private var micWant = false
    @Volatile private var mediaWant = false
    private var micThread: Thread? = null
    private var mediaThread: Thread? = null

    val micRunning: Boolean get() = micWant
    val mediaRunning: Boolean get() = mediaWant

    // ------------------------------------------------------------ 麦克风

    @Synchronized
    fun setMic(on: Boolean, context: Context) {
        if (on == micWant) return
        micWant = on
        if (on) {
            val t = Thread { micLoop(context.applicationContext) }
            t.isDaemon = true
            t.name = "audio-mic"
            micThread = t
            t.start()
        } else {
            micThread?.interrupt()
            micThread = null
            say("mic", "麦克风已关")
        }
    }

    @Synchronized
    fun setMedia(on: Boolean) {
        if (on == mediaWant) return
        mediaWant = on
        if (on) {
            val t = Thread { mediaLoop() }
            t.isDaemon = true
            t.name = "audio-media"
            mediaThread = t
            t.start()
        } else {
            mediaThread?.interrupt()
            mediaThread = null
            say("media", "电脑声音已关")
        }
    }

    fun stopAll() {
        setMedia(false)
        try { setMic(false, _ctx ?: return) } catch (_: Exception) { }
    }

    private var _ctx: Context? = null

    @SuppressLint("MissingPermission")
    private fun micLoop(ctx: Context) {
        _ctx = ctx
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, FRAME_BYTES * 4)
        while (micWant) {
            var rec: AudioRecord? = null
            var sock: Socket? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    say("mic", "麦克风打不开（可能被别的应用占着）")
                    Thread.sleep(1500)
                    continue
                }
                // 回声消除 / 降噪：手机外放电脑声音时，麦克风会把这声音再收回去，
                // 电脑那边就听到自己的回音。VOICE_COMMUNICATION 这条源本来就为通话优化，
                // 打开系统自带的 AEC 之后回音能压掉大半；不支持的机型 create() 返回 null。
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(rec.audioSessionId)
                        if (aec != null) aec.enabled = true
                        Diag.log("AUDIO", "mic 回声消除=" + (aec?.enabled == true))
                    } else {
                        Diag.log("AUDIO", "mic 本机不支持回声消除")
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(rec.audioSessionId)
                        if (ns != null) ns.enabled = true
                    }
                } catch (e: Exception) {
                    Diag.warn("AUDIO", "开回声消除失败：" + e.message)
                }
                sock = connect()
                val out = sock.getOutputStream()
                writeLine(out, "{\"t\":\"au\",\"dir\":\"mic\",\"rate\":$RATE,\"ch\":1}")
                val ack = readLine(sock.getInputStream())
                Diag.log("AUDIO", "mic ack=$ack")
                rec.startRecording()
                say("mic", "麦克风已开，说话就能进电脑")
                val buf = ByteArray(FRAME_BYTES)
                while (micWant) {
                    var got = 0
                    while (got < FRAME_BYTES) {
                        val n = rec.read(buf, got, FRAME_BYTES - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got < FRAME_BYTES) break
                    writeFrame(out, buf, got)
                }
            } catch (e: Exception) {
                if (micWant) {
                    Diag.warn("AUDIO", "麦克风通道断了：" + e.message)
                    say("mic", "麦克风重连中…")
                }
            } finally {
                try { aec?.release() } catch (_: Exception) { }
                try { ns?.release() } catch (_: Exception) { }
                try { rec?.stop() } catch (_: Exception) { }
                try { rec?.release() } catch (_: Exception) { }
                try { sock?.close() } catch (_: Exception) { }
            }
            if (!micWant) break
            try { Thread.sleep(800) } catch (_: InterruptedException) { break }
        }
        say("mic", "麦克风已关")
    }

    // ------------------------------------------------------------ 电脑声音

    private fun mediaLoop() {
        while (mediaWant) {
            var sock: Socket? = null
            var track: AudioTrack? = null
            try {
                sock = connect()
                // 和电脑端保持一致：接收缓冲压到 16 KB，别让几百 KB 的积压躺在缓冲里
                // 变成「越听越慢半拍」。宁可偶尔断一下，也要保证听到的是现在的声音。
                try {
                    sock.tcpNoDelay = true
                    sock.receiveBufferSize = 16384
                } catch (_: Exception) { }
                val ins = sock.getInputStream()
                val out = sock.getOutputStream()
                writeLine(out, "{\"t\":\"au\",\"dir\":\"media\"}")
                val ack = readLine(ins)
                Diag.log("AUDIO", "media ack=$ack")
                var rate = RATE
                var ch = 2
                try {
                    val o = org.json.JSONObject(ack)
                    rate = o.optInt("rate", RATE)
                    ch = o.optInt("ch", 2)
                    if (o.optString("t") == "a-err") {
                        say("media", "电脑端没法采集系统声音")
                        mediaWant = false
                        break
                    }
                } catch (_: Exception) { }

                val outCh = if (ch >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val minBuf = AudioTrack.getMinBufferSize(rate, outCh, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(outCh)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                say("media", "正在听电脑声音")
                val head = ByteArray(4)
                var buf = ByteArray(16384)
                var ducked = false
                var paused = false
                // 诊断用：每 2 秒报一次「收了多少包 / 写进去多少包 / 播放头到哪了」。
                // 落后 = 已经写进 AudioTrack 但还没播出去的音频秒数，正常应该稳定在 0.1 秒上下；
                // 一直往上涨就说明我们喂得比手机播得快（会越听越延迟）。
                val framesPerPacket = if (rate > 0) rate / 50 else 960
                var rxPackets = 0L
                var wrPackets = 0L
                var lastStat = SystemClock.elapsedRealtime()
                while (mediaWant) {
                    if (!readExact(ins, head, 4)) break
                    val n = (head[0].toInt() and 0xFF) or
                            ((head[1].toInt() and 0xFF) shl 8) or
                            ((head[2].toInt() and 0xFF) shl 16) or
                            ((head[3].toInt() and 0xFF) shl 24)
                    if (n <= 0 || n > 262144) break
                    // 回声处理，两层：
                    //   麦克风优先（默认）—— 开麦时直接暂停外放，对方绝对听不到回声；
                    //   否则退回「压低音量」（-9 dB），配合系统 AEC 削掉大半。
                    val wantPause = micPriority && micWant
                    if (wantPause != paused) {
                        paused = wantPause
                        try { if (paused) track.pause() else track.play() } catch (_: Exception) { }
                        say("media", if (paused) "开麦中，电脑声音已暂停（防止回声）" else "电脑声音恢复播放")
                    }
                    val wantDuck = !wantPause && micWant
                    if (wantDuck != ducked) {
                        ducked = wantDuck
                        try { track.setVolume(if (ducked) DUCK_VOLUME else 1f) } catch (_: Exception) { }
                        say("media", if (ducked) "开麦中，电脑声音已压低" else "电脑声音恢复音量")
                    }
                    if (n > buf.size) buf = ByteArray(n)
                    if (!readExact(ins, buf, n)) break
                    rxPackets++
                    // 暂停时照样要读帧，不然电脑端写满缓冲区会卡住整条链路
                    if (!paused) {
                        track.write(buf, 0, n)
                        wrPackets++
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStat >= 2000) {
                        lastStat = now
                        val played = try { track.playbackHeadPosition.toLong() / framesPerPacket } catch (_: Exception) { 0L }
                        val backlog = (wrPackets - played).coerceAtLeast(0) / 50.0
                        Diag.log("AUDIO", "media 进度 收=" + rxPackets + " 写=" + wrPackets +
                                " 播放头=" + played + " 落后=" + String.format(java.util.Locale.US, "%.2f", backlog) + "s" +
                                " 缓冲=" + (try { track.bufferSizeInFrames } catch (_: Exception) { 0 }) + "帧")
                    }
                }
            } catch (e: Exception) {
                if (mediaWant) {
                    Diag.warn("AUDIO", "电脑声音通道断了：" + e.message)
                    say("media", "电脑声音重连中…")
                }
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
                try { sock?.close() } catch (_: Exception) { }
            }
            if (!mediaWant) break
            try { Thread.sleep(800) } catch (_: InterruptedException) { break }
        }
        say("media", "电脑声音已关")
    }

    // ------------------------------------------------------------ 小工具

    private fun connect(): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 3000)
        return s
    }

    private fun writeLine(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun readLine(ins: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 4096) {
            val b = ins.read()
            if (b < 0) break
            if (b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun writeFrame(out: OutputStream, buf: ByteArray, len: Int) {
        val head = byteArrayOf(
            (len and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(),
            ((len shr 16) and 0xFF).toByte(),
            ((len shr 24) and 0xFF).toByte()
        )
        out.write(head)
        out.write(buf, 0, len)
        out.flush()
    }

    @Throws(IOException::class)
    private fun readExact(ins: InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = ins.read(buf, off, len - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun say(what: String, text: String) {
        onState?.invoke(what, text)
    }
}
