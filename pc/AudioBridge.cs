// AudioBridge.cs —— 手机当麦克风 / 电脑声音送到手机
//
// 走的是单独的一条 TCP 连接（AudioPort），跟主控链路（47777）完全分开：
//   · 音频是连续的二进制流，混在鼠标那条「一行一条 JSON」的连接里会把解析器搞乱；
//   · 分开之后，开麦克风、听电脑声音都不影响鼠标/手柄，掉线也各断各的。
//
// 一条音频连接只干一件事，靠第一行 JSON 握手说明方向：
//   手机→电脑   {"t":"a","dir":"mic","rate":48000,"ch":1}   然后发 PCM16 单声道
//   电脑→手机   {"t":"a","dir":"media"}                     然后收 PCM16 立体声
// 之后两个方向都是「4 字节小端长度 + 载荷」的定长帧。
// 电脑端先回一行 {"t":"a-ok","rate":..,"ch":..}，手机拿它去建 AudioTrack。
//
// 依赖 NAudio.dll（和 AirMouseServer.exe 放同一目录）。没有它就只有鼠标/手柄，
// 音频按钮点了会提示「缺少 NAudio.dll」，不会影响其它功能。

using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace AirMouse
{
    internal static class AudioBridge
    {
        private const int MaxFrame = 64 * 1024;
        // 电脑声音：抓系统的环回（WASAPI loopback），不用装任何东西
        private static WasapiLoopbackCapture _capture;
        private static readonly object Gate = new object();
        // 手机麦克风：把收到的 PCM 灌进 VB-Cable 的播放端，别的程序就能从「CABLE Output」录到
        private static BufferedWaveProvider _micBuffer;
        private static IWavePlayer _micOut;
        private static volatile bool _mediaBusy;
        private static volatile bool _micBusy;

        public static int MediaClients { get { return _mediaBusy ? 1 : 0; } }
        public static int MicClients { get { return _micBusy ? 1 : 0; } }

        public static void Handle(TcpClient client)
        {
            string remote = "?";
            try { remote = ((System.Net.IPEndPoint)client.Client.RemoteEndPoint).Address.ToString(); }
            catch { }
            string dir = null;
            try
            {
                using (client)
                using (var stream = client.GetStream())
                {
                    client.ReceiveTimeout = 0;
                    client.NoDelay = true;
                    // 音频要的是「新鲜」，不是「完整」：发送缓冲压到 16 KB（≈85ms 立体声），
                    // 链路一时跟不上时让背压马上顶回来，而不是把几秒钟的声音攒在缓冲里慢慢放。
                    try { client.SendBufferSize = 16384; } catch { }
                    try { client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true); } catch { }

                    string hello = ReadLine(stream);
                    if (hello == null) return;
                    Dictionary<string, object> o = null;
                    try { o = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(hello); }
                    catch { }
                    if (o == null) { Log.Write("[-] 音频连接握手不是 JSON：" + Short(hello)); return; }
                    // 注意：主控链路上 "a" 已经是「绝对坐标」，音频这边用 "au" 避免看日志时混淆
                    dir = Field(o, "t") == "au" ? Field(o, "dir") : null;
                    if (dir == null) { Log.Write("[-] 音频连接握手不认识：" + Short(hello)); return; }

                    if (dir == "media") RunMedia(stream, remote);
                    else if (dir == "mic") RunMic(stream, remote, Int(o, "rate", 48000), Int(o, "ch", 1));
                    else Log.Write("[-] 音频方向不认识：" + dir);
                }
            }
            catch (Exception ex)
            {
                Log.Write("[-] 音频连接异常（" + dir + " " + remote + "）：" + ex.Message);
            }
            finally
            {
                if (dir == "media") StopMedia();
                else if (dir == "mic") StopMic();
            }
        }

        // ---------------------------------------------------------------- 电脑声音 → 手机

        private static void RunMedia(NetworkStream stream, string remote)
        {
            lock (Gate)
            {
                if (_mediaBusy) { Log.Write("[i] 已经有手机在听电脑声音了，这条先拒绝"); return; }
                _mediaBusy = true;
            }
            int rate, ch;
            string devName;
            bool fellBack = false;
            try
            {
                var device = PickRenderDevice(out fellBack);
                _capture = new WasapiLoopbackCapture(device);
                rate = _capture.WaveFormat.SampleRate;
                ch = _capture.WaveFormat.Channels;
                devName = device.FriendlyName;
                if (fellBack)
                {
                    // 默认播放设备被设成了 VB-Cable：环回抓到的其实是灌进线缆的东西，
                    // 里面就有手机麦克风的声音，用户会听到自己的回声。这里改抓真正的
                    // 扬声器/耳机，并在日志里说清楚，免得再被当成 bug。
                    Log.Write("[i] 默认播放设备是 VB-Cable，已改抓「" + devName + "」（不然你会听到自己的回声）");
                }
            }
            catch (Exception ex)
            {
                Log.Write("[-] 打不开系统声音的环回采集：" + ex.Message);
                _mediaBusy = false;
                return;
            }

            var pending = new MemoryStream();
            var frameBytes = Math.Max(1, rate / 50) * ch * 2;   // 20ms 一帧
            // 发送侧改成「有界队列 + 独立发送线程」，队列最多存 200ms。
            // 以前是在采集回调里直接同步写 socket：链路抖一下（手机 GC、USB 抢带宽）就会
            // 把积压留在内核 / adb 转发缓冲里，攒够几秒后表现成「声音比画面慢 2 秒」，
            // 而且因为收发速率都是实时的，这个偏差永远追不回来。现在超过 200ms 就丢最旧的帧。
            const int MaxQueuedMs = 200;
            var maxQueuedBytes = Math.Max(frameBytes * 4, rate * ch * 2 / 1000 * MaxQueuedMs);
            var queue = new System.Collections.Generic.Queue<byte[]>();
            var qLock = new object();
            var queued = 0;
            var dropped = 0;
            var sendLock = new object();
            bool dead = false;

            var sender = new Thread(() =>
            {
                while (!dead)
                {
                    byte[] frame = null;
                    lock (qLock)
                    {
                        if (queue.Count > 0) { frame = queue.Dequeue(); queued -= frame.Length; }
                    }
                    if (frame == null) { Thread.Sleep(4); continue; }
                    try
                    {
                        lock (sendLock) WriteFrame(stream, frame);
                    }
                    catch { dead = true; return; }
                }
            });
            sender.IsBackground = true;

            _capture.DataAvailable += (s, e) =>
            {
                if (dead || e.BytesRecorded <= 0) return;
                var fmt = _capture.WaveFormat;
                var pcm = ToPcm16(e.Buffer, e.BytesRecorded, fmt);
                lock (pending)
                {
                    pending.Write(pcm, 0, pcm.Length);
                    while (pending.Length >= frameBytes)
                    {
                        pending.Position = 0;
                        var frame = new byte[frameBytes];
                        int got = pending.Read(frame, 0, frameBytes);
                        var rest = new byte[pending.Length - got];
                        pending.Read(rest, 0, rest.Length);
                        pending.SetLength(0);
                        pending.Write(rest, 0, rest.Length);
                        pending.Position = pending.Length;
                        lock (qLock)
                        {
                            queue.Enqueue(frame);
                            queued += frame.Length;
                            // 队列超了就把最旧的扔掉——宁可断一下，也不要越听越延迟
                            while (queued > maxQueuedBytes && queue.Count > 1)
                            {
                                var old = queue.Dequeue();
                                queued -= old.Length;
                                dropped++;
                            }
                        }
                    }
                }
            };
            _capture.RecordingStopped += (s, e) =>
            {
                if (e.Exception != null) Log.Write("[-] 系统声音采集停了：" + e.Exception.Message);
            };

            try
            {
                WriteLine(stream, "{\"t\":\"a-ok\",\"dir\":\"media\",\"rate\":" + rate + ",\"ch\":" + ch + "}");
                _capture.StartRecording();
                sender.Start();
                Log.Write("[i] 开始把电脑声音送给手机 " + remote + "（" + rate + " Hz · " + ch + " 声道 · 抓的是「" + devName + "」）");
                // 不用主动探测连接：写失败时 WriteFrame 会抛，dead 置上就退出了
                var lastDropLog = DateTime.UtcNow;
                while (!dead && _mediaBusy)
                {
                    Thread.Sleep(200);
                    // 丢帧只在真跟不上时发生，日志别刷屏：最多 5 秒一条
                    if ((DateTime.UtcNow - lastDropLog).TotalSeconds >= 5)
                    {
                        int d;
                        lock (qLock) { d = dropped; dropped = 0; }
                        if (d > 0)
                        {
                            lastDropLog = DateTime.UtcNow;
                            Log.Write("[i] 发送跟不上，丢掉 " + d + " 帧（" + (d * 20) + "ms）以保证不延迟");
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Write("[-] 送音频出错：" + ex.Message);
            }
            finally
            {
                StopMedia();
                Log.Write("[i] 停止送电脑声音（" + remote + "）");
            }
        }

        private static void StopMedia()
        {
            _mediaBusy = false;
            var c = _capture;
            _capture = null;
            if (c != null)
            {
                try { c.StopRecording(); } catch { }
                try { c.Dispose(); } catch { }
            }
        }

        // ---------------------------------------------------------------- 手机麦克风 → 电脑

        private static void RunMic(NetworkStream stream, string remote, int rate, int ch)
        {
            if (rate < 8000 || rate > 96000) rate = 48000;
            if (ch < 1 || ch > 2) ch = 1;
            lock (Gate)
            {
                if (_micBusy) { Log.Write("[i] 已经有手机在当麦克风了，这条先拒绝"); return; }
                _micBusy = true;
            }
            try
            {
                int dev = FindCableInput();
                if (dev < 0)
                {
                    Log.Write("[-] 找不到 VB-Cable 的「CABLE Input」播放设备：先把 VB-Cable 装上，麦克风才能进电脑");
                    WriteLine(stream, "{\"t\":\"a-err\",\"why\":\"no-cable\"}");
                    return;
                }
                var fmt = new WaveFormat(rate, 16, ch);
                _micBuffer = new BufferedWaveProvider(fmt)
                {
                    BufferDuration = TimeSpan.FromMilliseconds(400),
                    DiscardOnBufferOverflow = true,
                    ReadFully = true            // 没数据时补静音，避免设备卡住
                };
                _micOut = new WaveOutEvent { DeviceNumber = dev, DesiredLatency = 120 };
                _micOut.Init(_micBuffer);
                _micOut.Play();
                WriteLine(stream, "{\"t\":\"a-ok\",\"dir\":\"mic\",\"rate\":" + rate + ",\"ch\":" + ch + ",\"dev\":\"CABLE Input\"}");
                Log.Write("[i] 手机 " + remote + " 开始当麦克风（" + rate + " Hz · " + ch + " 声道 → CABLE Input）");

                var lenBuf = new byte[4];
                var buf = new byte[MaxFrame];
                while (_micBusy)
                {
                    if (!ReadFrame(stream, lenBuf, buf)) break;
                    int n = BitConverter.ToInt32(lenBuf, 0);
                    if (n <= 0) continue;
                    try { _micBuffer.AddSamples(buf, 0, n); } catch { }
                }
                Log.Write("[i] 手机 " + remote + " 停止当麦克风");
            }
            catch (Exception ex)
            {
                Log.Write("[-] 麦克风通道出错：" + ex.Message);
            }
            finally
            {
                StopMic();
            }
        }

        private static void StopMic()
        {
            _micBusy = false;
            var o = _micOut;
            _micOut = null;
            if (o != null)
            {
                try { o.Stop(); } catch { }
                try { o.Dispose(); } catch { }
            }
            _micBuffer = null;
        }

        // VB-Cable 的播放端在 MME 里叫「CABLE Input (VB-Audio Virtual Cable)」
        private static int FindCableInput()
        {
            try
            {
                int n = WaveOut.DeviceCount;
                for (int i = 0; i < n; i++)
                {
                    var cap = WaveOut.GetCapabilities(i);
                    if (cap.ProductName != null && cap.ProductName.IndexOf("CABLE", StringComparison.OrdinalIgnoreCase) >= 0)
                        return i;
                }
            }
            catch { }
            return -1;
        }

        // 挑「电脑声音」该从哪个设备抓。
        // 正常情况就是系统的默认播放设备。但如果用户（或 VB-Cable 安装程序）把 VB-Cable
        // 设成了默认播放设备，环回抓到的就是灌进线缆的东西——里面有手机麦克风的声音，
        // 表现就是「我一说话手机里就响我的声音」。这时改抓第一块真正的声卡，并在日志里说明。
        private static MMDevice PickRenderDevice(out bool fellBack)
        {
            fellBack = false;
            var en = new MMDeviceEnumerator();
            MMDevice def = null;
            try { def = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia); }
            catch { }
            if (def != null && !IsCableLike(def.FriendlyName)) return def;
            try
            {
                foreach (var d in en.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
                {
                    if (IsCableLike(d.FriendlyName)) continue;
                    fellBack = true;
                    return d;
                }
            }
            catch { }
            return def;
        }

        private static bool IsCableLike(string name)
        {
            if (string.IsNullOrEmpty(name)) return false;
            return name.IndexOf("CABLE", StringComparison.OrdinalIgnoreCase) >= 0
                || name.IndexOf("VB-Audio", StringComparison.OrdinalIgnoreCase) >= 0;
        }

        // ---------------------------------------------------------------- 小工具

        // WASAPI 环回给的是 float32（通常是 48k 立体声），手机端要 PCM16
        private static byte[] ToPcm16(byte[] buf, int bytes, WaveFormat fmt)
        {
            if (fmt.Encoding == WaveFormatEncoding.IeeeFloat && fmt.BitsPerSample == 32)
            {
                int samples = bytes / 4;
                var outb = new byte[samples * 2];
                for (int i = 0; i < samples; i++)
                {
                    float f = BitConverter.ToSingle(buf, i * 4);
                    int v = (int)(f * 32767f);
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    short s = (short)v;
                    outb[i * 2] = (byte)(s & 0xFF);
                    outb[i * 2 + 1] = (byte)((s >> 8) & 0xFF);
                }
                return outb;
            }
            if (fmt.BitsPerSample == 16)
            {
                var outb = new byte[bytes];
                Buffer.BlockCopy(buf, 0, outb, 0, bytes);
                return outb;
            }
            // 其它格式先当静音，宁可没声音也不要噪音
            return new byte[0];
        }

        private static void WriteFrame(NetworkStream s, byte[] payload)
        {
            var head = BitConverter.GetBytes(payload.Length);
            s.Write(head, 0, 4);
            s.Write(payload, 0, payload.Length);
        }

        private static bool ReadFrame(NetworkStream s, byte[] lenBuf, byte[] buf)
        {
            if (!ReadExact(s, lenBuf, 4)) return false;
            int n = BitConverter.ToInt32(lenBuf, 0);
            if (n <= 0 || n > buf.Length) return false;
            return ReadExact(s, buf, n);
        }

        private static bool ReadExact(NetworkStream s, byte[] buf, int count)
        {
            int off = 0;
            while (off < count)
            {
                int got;
                try { got = s.Read(buf, off, count - off); }
                catch (IOException) { return false; }
                catch (ObjectDisposedException) { return false; }
                if (got <= 0) return false;
                off += got;
            }
            return true;
        }

        private static string ReadLine(NetworkStream s)
        {
            var sb = new StringBuilder();
            var one = new byte[1];
            for (int i = 0; i < 4096; i++)
            {
                if (!ReadExact(s, one, 1)) return sb.Length > 0 ? sb.ToString() : null;
                if (one[0] == (byte)'\n') return sb.ToString();
                sb.Append((char)one[0]);
            }
            return sb.ToString();
        }

        private static void WriteLine(NetworkStream s, string line)
        {
            var b = Encoding.UTF8.GetBytes(line + "\n");
            s.Write(b, 0, b.Length);
            s.Flush();
        }

        private static string Short(string s)
        {
            if (s == null) return "null";
            return s.Length <= 80 ? s : s.Substring(0, 80) + "…";
        }

        private static string Field(Dictionary<string, object> m, string k)
        {
            object v;
            return m != null && m.TryGetValue(k, out v) && v != null ? v.ToString() : null;
        }

        private static int Int(Dictionary<string, object> m, string k, int def)
        {
            object v;
            if (m == null || !m.TryGetValue(k, out v) || v == null) return def;
            try { return Convert.ToInt32(v); } catch { return def; }
        }
    }
}
