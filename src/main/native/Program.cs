using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Threading;

internal static class Program
{
    private const long MinimumStart = 5L * 1024 * 1024 * 1024;
    private const long MinimumContinue = 1024L * 1024 * 1024;

    [MTAThread]
    private static int Main(string[] args)
    {
        try
        {
            if (args.Length == 1 && (args[0] == "protect-invitation" || args[0] == "unprotect-invitation"))
            {
                using (var input = Console.OpenStandardInput())
                using (var output = Console.OpenStandardOutput())
                using (var buffer = new MemoryStream())
                {
                    input.CopyTo(buffer);
                    if (buffer.Length == 0 || buffer.Length > 16384) return 2;
                    byte[] data = buffer.ToArray();
                    byte[] result = args[0] == "protect-invitation"
                        ? ProtectedData.Protect(data, null, DataProtectionScope.CurrentUser)
                        : ProtectedData.Unprotect(data, null, DataProtectionScope.CurrentUser);
                    output.Write(result, 0, result.Length);
                }
                return 0;
            }
            if (args.Length == 2 && args[0] == "recover")
            {
                WaveChunks.Recover(Path.GetFullPath(args[1]));
                return 0;
            }
            uint pid;
            long creation;
            if (args.Length != 4 || (args[0] != "capture" && args[0] != "capture-ms") ||
                !uint.TryParse(args[1], NumberStyles.None, CultureInfo.InvariantCulture, out pid) ||
                pid == 0 || pid > int.MaxValue ||
                !long.TryParse(args[2], NumberStyles.None, CultureInfo.InvariantCulture, out creation))
                return 2;
            using (var target = Process.GetProcessById((int)pid))
            {
                // Keep the validated Windows process object open for the lifetime of capture.
                var processHandle = target.Handle;
                long actual = target.StartTime.ToFileTimeUtc();
                if (args[0] == "capture-ms") actual /= 10000;
                if (target.HasExited || actual != creation || processHandle == IntPtr.Zero)
                    return 2;
                Capture(pid, target, Path.GetFullPath(args[3]));
            }
            return 0;
        }
        catch (Exception error)
        {
            Emit("error", "hresult", Marshal.GetHRForException(error));
            return 1;
        }
    }

    private static void Capture(uint pid, Process target, string directory)
    {
        var drive = new DriveInfo(Path.GetPathRoot(directory));
        if (drive.AvailableFreeSpace < MinimumStart) throw new IOException("Disk space");
        // The background thread exits with this one. The event stays alive while stdin is open.
        var stop = new ManualResetEvent(false);
        var control = new Thread(() =>
        {
            try { Console.ReadLine(); }
            catch (IOException) { }
            finally { stop.Set(); }
        });
        control.IsBackground = true;
        control.Start();

        using (var ready = new AutoResetEvent(false))
        using (var chunks = new WaveChunks(directory))
        {
            var audio = Wasapi.Activate(pid, stop);
            if (audio == null || stop.WaitOne(0)) return;
            try
            {
                var capture = Wasapi.Initialize(audio, ready);
                try
                {
                    long start = Clock100ns();
                    Wasapi.Check(audio.Start());
                    Emit("started", "pid", pid);
                    try { ReadPackets(capture, target, stop, ready, chunks, drive, start); }
                    finally { audio.Stop(); }
                }
                finally { Marshal.ReleaseComObject(capture); }
            }
            finally { Marshal.ReleaseComObject(audio); }
            chunks.CloseChunk();
            Emit("stopped", "chunks", chunks.Count);
        }
    }

    private static void ReadPackets(Wasapi.ICaptureClient capture, Process target, WaitHandle stop, WaitHandle ready,
        WaveChunks chunks, DriveInfo drive, long start)
    {
        var signals = new[] { stop, ready };
        var buffer = new byte[0];
        long previousEnd = 0;
        long reportAt = start;
        int peak = 0;
        while (WaitHandle.WaitAny(signals, 500) != 0)
        {
            if (target.HasExited) throw new IOException("Target exited");
            uint pending;
            Wasapi.Check(capture.GetNextPacketSize(out pending));
            while (pending > 0 && !stop.WaitOne(0))
            {
                IntPtr data;
                uint count, flags;
                ulong position, timestamp;
                Wasapi.Check(capture.GetBuffer(out data, out count, out flags, out position, out timestamp));
                if (count == 0) break;
                try
                {
                    int bytes = checked((int)count * Wasapi.FrameBytes);
                    if (buffer.Length < bytes) buffer = new byte[bytes];
                    if ((flags & Wasapi.Silent) != 0) Array.Clear(buffer, 0, bytes);
                    else Marshal.Copy(data, buffer, 0, bytes);

                    long frame = previousEnd;
                    if ((flags & Wasapi.TimestampError) == 0)
                    {
                        long measured = Math.Max(0L, ((long)timestamp - start) * Wasapi.Rate / 10000000);
                        if (previousEnd == 0 || ((flags & Wasapi.Discontinuity) != 0 &&
                            measured > previousEnd + Wasapi.Rate / 100)) frame = Math.Max(previousEnd, measured);
                    }
                    if (frame > previousEnd)
                    {
                        chunks.CloseChunk();
                        Console.WriteLine("{{\"event\":\"gap\",\"startFrame\":{0},\"endFrame\":{1}}}",
                            previousEnd, frame);
                    }
                    if (!stop.WaitOne(0)) chunks.Write(buffer, bytes, frame);
                    previousEnd = frame + count;
                    for (int offset = 0; offset < bytes; offset += 2)
                        peak = Math.Max(peak, Math.Abs((int)(short)(buffer[offset] | buffer[offset + 1] << 8)));
                }
                finally { Wasapi.Check(capture.ReleaseBuffer(count)); }
                Wasapi.Check(capture.GetNextPacketSize(out pending));
            }
            long now = Clock100ns();
            if (now < reportAt) continue;
            if (drive.AvailableFreeSpace < MinimumContinue) throw new IOException("Disk space");
            Console.WriteLine("{{\"event\":\"level\",\"peak\":{0},\"frames\":{1}}}", peak, previousEnd);
            peak = 0;
            reportAt = now + 2500000;
        }
    }

    private static long Clock100ns()
    {
        return (long)((double)Stopwatch.GetTimestamp() * 10000000 / Stopwatch.Frequency);
    }

    private static void Emit(string name, string key, long value)
    {
        Console.WriteLine("{{\"event\":\"{0}\",\"{1}\":{2}}}", name, key, value);
    }
}
