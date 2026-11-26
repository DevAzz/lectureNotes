using System;
using System.Runtime.InteropServices;
using System.Threading;

// Shared-mode process loopback. This interfaces with the Windows audio engine only.
internal static class Wasapi
{
    internal const int Rate = 44100;
    internal const int Channels = 2;
    internal const int FrameBytes = 4;
    internal const uint Discontinuity = 1;
    internal const uint Silent = 2;
    internal const uint TimestampError = 4;

    private const uint StreamFlags = 0x00020000 | 0x00040000 | 0x80000000;
    private const ushort VtBlob = 65;

    [StructLayout(LayoutKind.Sequential)]
    private struct ActivationParameters
    {
        internal uint Type;
        internal uint ProcessId;
        internal uint Mode;
    }

    // x64 PROPVARIANT containing a BLOB.
    [StructLayout(LayoutKind.Explicit, Size = 24)]
    private struct ActivationVariant
    {
        [FieldOffset(0)] internal ushort Type;
        [FieldOffset(8)] internal int Length;
        [FieldOffset(16)] internal IntPtr Data;
    }

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    internal struct WaveFormat
    {
        internal ushort Tag, ChannelCount;
        internal uint SampleRate, BytesPerSecond;
        internal ushort BlockAlign, BitsPerSample, ExtraSize;
    }

    [DllImport("mmdevapi.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern int ActivateAudioInterfaceAsync(string device, ref Guid iid,
        ref ActivationVariant activation, IActivationHandler callback, out IActivationOperation operation);

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"),
        InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IActivationOperation
    {
        [PreserveSig] int GetActivateResult(out int result, [MarshalAs(UnmanagedType.IUnknown)] out object audio);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"),
        InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IActivationHandler
    {
        [PreserveSig] int ActivateCompleted(IActivationOperation operation);
    }

    [ComImport, Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"),
        InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAgileObject { }

    [ComVisible(true), ClassInterface(ClassInterfaceType.None)]
    private sealed class ActivationHandler : IActivationHandler, IAgileObject
    {
        internal readonly ManualResetEvent Done = new ManualResetEvent(false);
        internal IAudioClient Client;
        internal Exception Failure;

        public int ActivateCompleted(IActivationOperation operation)
        {
            try
            {
                int result;
                object audio;
                Check(operation.GetActivateResult(out result, out audio));
                Check(result);
                Client = (IAudioClient)audio;
            }
            catch (Exception error) { Failure = error; }
            finally { Done.Set(); }
            return 0;
        }
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"),
        InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, uint flags, long duration, long period,
            ref WaveFormat format, IntPtr session);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int mode, ref WaveFormat format, IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long normal, out long minimum);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"),
        InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface ICaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags,
            out ulong position, out ulong timestamp);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    internal static IAudioClient Activate(uint pid, WaitHandle stop)
    {
        var parameters = new ActivationParameters { Type = 1, ProcessId = pid, Mode = 0 };
        int size = Marshal.SizeOf(parameters);
        IntPtr memory = Marshal.AllocHGlobal(size);
        var handler = new ActivationHandler();
        IActivationOperation operation = null;
        try
        {
            Marshal.StructureToPtr(parameters, memory, false);
            var activation = new ActivationVariant { Type = VtBlob, Length = size, Data = memory };
            Guid iid = typeof(IAudioClient).GUID;
            Check(ActivateAudioInterfaceAsync(@"VAD\Process_Loopback", ref iid,
                ref activation, handler, out operation));
            int signal = WaitHandle.WaitAny(new[] { handler.Done, stop }, 10000);
            if (signal == 1) return null;
            if (signal == WaitHandle.WaitTimeout) throw new TimeoutException();
            if (handler.Failure != null) throw handler.Failure;
            return handler.Client;
        }
        finally
        {
            if (operation != null) Marshal.ReleaseComObject(operation);
            Marshal.FreeHGlobal(memory);
            GC.KeepAlive(handler);
        }
    }

    internal static ICaptureClient Initialize(IAudioClient client, EventWaitHandle ready)
    {
        var format = new WaveFormat {
            Tag = 1, ChannelCount = Channels, SampleRate = Rate, BytesPerSecond = Rate * FrameBytes,
            BlockAlign = FrameBytes, BitsPerSample = 16
        };
        Check(client.Initialize(0, StreamFlags, 0, 0, ref format, IntPtr.Zero));
        Check(client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle()));
        Guid iid = typeof(ICaptureClient).GUID;
        object service;
        Check(client.GetService(ref iid, out service));
        return (ICaptureClient)service;
    }

    internal static void Check(int result)
    {
        if (result < 0) Marshal.ThrowExceptionForHR(result, new IntPtr(-1));
    }
}
