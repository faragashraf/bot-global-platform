using BotGlobal.Communication.Application.Chat;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Communication;

public sealed class ChatVoiceStorageTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "botglobal-chat-tests", Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task Publish_ValidatesContainerAndHashesExactBytes()
    {
        var storage = CreateStorage();
        var bytes = await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory, "Communication/Fixtures/aac-silence.m4a"));
        var upload = await storage.PublishAsync(new MemoryStream(bytes), "audio/mp4", default);
        Assert.Equal(bytes.Length, upload.Length);
        Assert.InRange(upload.DurationMilliseconds, 1, 300_000);
        Assert.Equal(Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes)).ToLowerInvariant(), upload.Sha256);
        await using var saved = storage.OpenRead(upload.FileKey);
        Assert.Equal(bytes, ReadAll(saved));
        Assert.DoesNotContain(storage.EnumerateFileKeys(), x => x.EndsWith(".partial", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Publish_RejectsSpoofedAndOversizedContentWithoutPartial()
    {
        var storage = CreateStorage();
        await Assert.ThrowsAsync<InvalidDataException>(() => storage.PublishAsync(new MemoryStream(new byte[32]), "audio/mp4", default));
        await Assert.ThrowsAsync<InvalidDataException>(() => storage.PublishAsync(new MemoryStream(ValidM4aBytes(256)), "audio/mp4", default));
        await Assert.ThrowsAsync<InvalidDataException>(() => storage.PublishAsync(new RepeatingM4aStream(10 * 1024 * 1024 + 1), "audio/mp4", default));
        Assert.Empty(storage.EnumerateFileKeys());
    }

    [Fact]
    public async Task Publish_RejectsTruncatedVideoNonAacAndActualOverlongTracks()
    {
        var valid = await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory, "Communication/Fixtures/aac-silence.m4a"));
        var storage = CreateStorage();
        await Assert.ThrowsAsync<InvalidDataException>(() => storage.PublishAsync(new MemoryStream(valid[..^8]), "audio/mp4", default));
        foreach (var pair in new[] { ("soun", "vide"), ("mp4a", "alac") })
        {
            var bytes = valid.ToArray(); var at = Find(bytes, pair.Item1);
            System.Text.Encoding.ASCII.GetBytes(pair.Item2).CopyTo(bytes, at);
            await Assert.ThrowsAsync<InvalidDataException>(() => storage.PublishAsync(new MemoryStream(bytes), "audio/mp4", default));
        }
        var overlong = valid.ToArray(); var header = Find(overlong, "mdhd");
        // A real audio track with an actual media duration > 5 minutes is rejected regardless of caller headers.
        System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(overlong.AsSpan(header + 20, 4), uint.MaxValue);
        await Assert.ThrowsAsync<InvalidDataException>(() => storage.PublishAsync(new MemoryStream(overlong), "audio/mp4", default));
        Assert.Empty(storage.EnumerateFileKeys());
    }
    private static int Find(byte[] bytes, string value) => bytes.AsSpan().IndexOf(System.Text.Encoding.ASCII.GetBytes(value));

    [Fact]
    public async Task Publish_RejectsCorruptPayloadExternalReferencesAndMissingDecoderWithoutPublication()
    {
        var bytes = await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory, "Communication/Fixtures/aac-silence.m4a"));
        var storage = CreateStorage();
        var corrupt = bytes.ToArray(); var at = Find(corrupt, "mdat");
        var size = System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(corrupt.AsSpan(at - 4, 4));
        Array.Clear(corrupt, at + 4, size - 8);
        Assert.InRange(ChatAacContainer.Validate(corrupt), 1, 300_000);
        await Assert.ThrowsAsync<InvalidDataException>(() => storage.PublishAsync(new MemoryStream(corrupt), "audio/mp4", default));
        var external = bytes.ToArray(); at = Find(external, "url "); external[at + 7] = 0;
        await Assert.ThrowsAsync<InvalidDataException>(() => storage.PublishAsync(new MemoryStream(external), "audio/mp4", default));
        var unavailable = new PrivateChatVoiceStorage(Options.Create(new ChatVoiceOptions {
            StoragePath = _root, DecoderPath = Path.Combine(_root, "absent-decoder") }), new FakeEnvironment(_root));
        await Assert.ThrowsAsync<InvalidDataException>(() => unavailable.PublishAsync(new MemoryStream(bytes), "audio/mp4", default));
        Assert.Empty(storage.EnumerateFileKeys());
    }

    [Fact]
    public void FileKeys_CannotEscapePrivateRoot()
    {
        var storage = CreateStorage();
        Assert.Throws<InvalidDataException>(() => storage.OpenRead("../outside.m4a"));
    }

    private PrivateChatVoiceStorage CreateStorage() => new(
        Options.Create(new ChatVoiceOptions { StoragePath = _root }), new FakeEnvironment(_root));
    private static byte[] ValidM4aBytes(int length)
    {
        var bytes = new byte[length]; bytes[3] = 24; bytes[4] = (byte)'f'; bytes[5] = (byte)'t'; bytes[6] = (byte)'y'; bytes[7] = (byte)'p';
        bytes[8] = (byte)'M'; bytes[9] = (byte)'4'; bytes[10] = (byte)'A'; bytes[11] = (byte)' '; return bytes;
    }
    private static byte[] ReadAll(Stream input) { using var memory = new MemoryStream(); input.CopyTo(memory); return memory.ToArray(); }
    public void Dispose() { if (Directory.Exists(_root)) Directory.Delete(_root, true); }

    private sealed class FakeEnvironment(string root) : IHostEnvironment
    {
        public string EnvironmentName { get; set; } = Environments.Development;
        public string ApplicationName { get; set; } = "Tests";
        public string ContentRootPath { get; set; } = root;
        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
    }

    private sealed class RepeatingM4aStream(long length) : Stream
    {
        private long _position;
        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_position >= length) return 0;
            var read = (int)Math.Min(count, length - _position);
            Array.Clear(buffer, offset, read);
            if (_position == 0 && read >= 12) { buffer[offset + 4] = (byte)'f'; buffer[offset + 5] = (byte)'t'; buffer[offset + 6] = (byte)'y'; buffer[offset + 7] = (byte)'p'; }
            _position += read; return read;
        }
        public override bool CanRead => true; public override bool CanSeek => false; public override bool CanWrite => false;
        public override long Length => length; public override long Position { get => _position; set => throw new NotSupportedException(); }
        public override void Flush() { } public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException(); public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
