using System;
using System.Globalization;
using System.IO;
using System.Text;

internal sealed class WaveChunks : IDisposable
{
    private const int Limit = 60 * Wasapi.Rate * Wasapi.FrameBytes;
    private readonly string directory;
    private FileStream stream;
    private string path;
    private int bytes;
    private int sequence;
    internal int Count { get { return sequence; } }

    internal WaveChunks(string directory) { this.directory = directory; }

    internal void Write(byte[] buffer, int count, long startFrame)
    {
        int offset = 0;
        while (offset < count)
        {
            if (stream == null) Open(startFrame + offset / Wasapi.FrameBytes);
            int length = Math.Min(count - offset, Limit - bytes);
            stream.Write(buffer, offset, length);
            bytes += length;
            offset += length;
            if (bytes == Limit) CloseChunk();
        }
    }

    private void Open(long startFrame)
    {
        string name = string.Format(CultureInfo.InvariantCulture, "{0:D5}-{1:D12}.wav", sequence, startFrame);
        path = Path.Combine(directory, name);
        stream = new FileStream(path + ".part", FileMode.CreateNew, FileAccess.Write, FileShare.Read);
        Header(stream, 0);
        stream.Flush(true);
    }

    internal void CloseChunk()
    {
        if (stream == null) return;
        try
        {
            stream.Position = 0;
            Header(stream, bytes);
            stream.Flush(true);
        }
        finally
        {
            stream.Dispose();
            stream = null;
        }
        File.Move(path + ".part", path);
        sequence++;
        bytes = 0;
    }

    internal static void Recover(string directory)
    {
        foreach (string file in Directory.GetFiles(directory, "*.wav.part"))
        {
            using (var partial = new FileStream(file, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            {
                long data = partial.Length - 44;
                if (data < Wasapi.FrameBytes || data > Limit) continue;
                var signature = new byte[12];
                if (partial.Read(signature, 0, signature.Length) != signature.Length ||
                    Encoding.ASCII.GetString(signature, 0, 4) != "RIFF" ||
                    Encoding.ASCII.GetString(signature, 8, 4) != "WAVE") continue;
                int aligned = (int)(data / Wasapi.FrameBytes) * Wasapi.FrameBytes;
                partial.SetLength(44 + aligned);
                partial.Position = 0;
                Header(partial, aligned);
                partial.Flush(true);
            }
            File.Move(file, file.Substring(0, file.Length - 5));
        }
    }

    private static void Header(Stream stream, int length)
    {
        using (var writer = new BinaryWriter(stream, Encoding.ASCII, true))
        {
            writer.Write(Encoding.ASCII.GetBytes("RIFF"));
            writer.Write(36 + length);
            writer.Write(Encoding.ASCII.GetBytes("WAVEfmt "));
            writer.Write(16);
            writer.Write((short)1);
            writer.Write((short)Wasapi.Channels);
            writer.Write(Wasapi.Rate);
            writer.Write(Wasapi.Rate * Wasapi.FrameBytes);
            writer.Write((short)Wasapi.FrameBytes);
            writer.Write((short)16);
            writer.Write(Encoding.ASCII.GetBytes("data"));
            writer.Write(length);
        }
    }

    public void Dispose() { CloseChunk(); }
}
