using System.Diagnostics;
using BotGlobal.Communication.Application.Chat;

namespace BotGlobal.UnitTests.Communication;

public sealed class ChatAacDecoderTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "chat-decoder-tests", Guid.NewGuid().ToString("N"));
    private static string Decoder => File.Exists("/opt/homebrew/bin/ffmpeg") ? "/opt/homebrew/bin/ffmpeg" : "ffmpeg";
    private static string Fixture => Path.Combine(AppContext.BaseDirectory, "Communication/Fixtures/aac-silence.m4a");

    [Fact]
    public void FinalizedDurationMatchesSharedMobileSampleTimeOracle()
    {
        var bytes = File.ReadAllBytes(Fixture);
        Assert.Equal("ce66f2e7c1e9c8d940ed0b56b476f1900ba6f0cfacdb71b0cd1b43f5ddc26411",
            Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes)).ToLowerInvariant());
        var at = bytes.AsSpan().IndexOf("stts"u8) + 4;
        uint Read(int offset) => System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(at + offset, 4));
        Assert.Equal(2u, Read(4));
        var samples = (ulong)Read(8) * Read(12) + (ulong)Read(16) * Read(20);
        Assert.Equal(89224ul, samples);
        var header = bytes.AsSpan().IndexOf("mdhd"u8) + 4;
        var scale = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(header + 12, 4));
        Assert.Equal(44100u, scale);
        Assert.Equal(2024, ChatAacContainer.Validate(bytes));
        Assert.Equal((int)((samples * 1000 + scale - 1) / scale), ChatAacContainer.Validate(bytes));
        Assert.NotEqual(2179, ChatAacContainer.Validate(bytes)); // Deliberately different recorder timer.
    }

    [Fact]
    public void MediaRecorderTimescaleMayDifferFromAacSampleRate()
    {
        var bytes = File.ReadAllBytes(Fixture);
        WriteBoxValue(bytes, "mdhd"u8, 12, 1000);
        WriteBoxValue(bytes, "mdhd"u8, 16, 2024);
        WriteBoxValue(bytes, "stts"u8, 12, 23);
        WriteBoxValue(bytes, "stts"u8, 20, 23);
        Assert.Equal(2024, ChatAacContainer.Validate(bytes));
    }

    [Fact]
    public async Task EstablishedDecoderAcceptsSyntheticSilenceAndRejectsContainerValidPayloadCorruption()
    {
        Directory.CreateDirectory(_root);
        var decoder = new ChatAacDecoder(Decoder);
        await decoder.ValidateAsync(Fixture, default);
        var bytes = await File.ReadAllBytesAsync(Fixture);
        var at = bytes.AsSpan().IndexOf("mdat"u8);
        var size = System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(at - 4, 4));
        Array.Clear(bytes, at + 4, size - 8);
        Assert.InRange(ChatAacContainer.Validate(bytes), 1, 300_000);
        var corrupt = Path.Combine(_root, "corrupt.m4a");
        await File.WriteAllBytesAsync(corrupt, bytes);
        await Assert.ThrowsAsync<InvalidDataException>(() => decoder.ValidateAsync(corrupt, default));
    }

    [Fact]
    public async Task MissingDecoderFailsClosed()
    {
        var error = await Assert.ThrowsAsync<InvalidDataException>(() =>
            new ChatAacDecoder(Path.Combine(_root, "absent-decoder")).ValidateAsync(Fixture, default));
        Assert.Equal("chat_voice_decoder_unavailable", error.Message);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task TimeoutAndCancellationKillAndReapOwnedProcess(bool cancel)
    {
        if (OperatingSystem.IsWindows()) return; // Unix process fixture; production decoder remains cross-platform.
        Directory.CreateDirectory(_root);
        var executable = Path.Combine(_root, "slow-decoder");
        var pidFile = Path.Combine(_root, "pid");
        // A local test executable, not a product shell command. No media/credentials or external calls.
        await File.WriteAllTextAsync(executable, $"#!/bin/sh\necho $$ > '{pidFile}'\nexec /bin/sleep 30\n");
        File.SetUnixFileMode(executable, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        using var cancellation = new CancellationTokenSource();
        var watch = Stopwatch.StartNew();
        var operation = new ChatAacDecoder(executable, TimeSpan.FromSeconds(2)).ValidateAsync(Fixture, cancellation.Token);
        while (!File.Exists(pidFile) && !operation.IsCompleted) await Task.Delay(10);
        Assert.True(File.Exists(pidFile));
        var pid = int.Parse(await File.ReadAllTextAsync(pidFile));
        if (cancel) { cancellation.Cancel(); await Assert.ThrowsAnyAsync<OperationCanceledException>(() => operation); }
        else await Assert.ThrowsAsync<InvalidDataException>(() => operation);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(10));
        Assert.Throws<ArgumentException>(() => Process.GetProcessById(pid));
    }

    [Fact]
    public async Task ConcurrencyIsBoundedAndCapacitySurvivesFailures()
    {
        if (!OperatingSystem.IsWindows())
        {
            Directory.CreateDirectory(_root);
            using var stop = new CancellationTokenSource();
            var operations = new List<Task>();
            for (var i = 0; i < 6; i++)
            {
                var executable = Path.Combine(_root, $"waiting-{i}");
                await File.WriteAllTextAsync(executable, $"#!/bin/sh\necho $$ > '{executable}.pid'\nexec /bin/sleep 30\n");
                File.SetUnixFileMode(executable, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
                operations.Add(new ChatAacDecoder(executable).ValidateAsync(Fixture, stop.Token));
            }
            try
            {
                var watch = Stopwatch.StartNew();
                while (Directory.GetFiles(_root, "waiting-*.pid").Length < 2 && watch.Elapsed < TimeSpan.FromSeconds(5)) await Task.Delay(10);
                await Task.Delay(100);
                Assert.Equal(2, Directory.GetFiles(_root, "waiting-*.pid").Length);
            }
            finally
            {
                stop.Cancel();
                foreach (var operation in operations) await Assert.ThrowsAnyAsync<OperationCanceledException>(() => operation);
            }
            foreach (var file in Directory.GetFiles(_root, "waiting-*.pid"))
            {
                var pid = int.Parse(await File.ReadAllTextAsync(file));
                Assert.Throws<ArgumentException>(() => Process.GetProcessById(pid));
            }
        }
        var decoder = new ChatAacDecoder(Decoder);
        await Task.WhenAll(Enumerable.Range(0, 12).Select(_ => decoder.ValidateAsync(Fixture, default)));
        using var cancelled = new CancellationTokenSource(); cancelled.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => decoder.ValidateAsync(Fixture, cancelled.Token));
        await decoder.ValidateAsync(Fixture, default);
    }
    public void Dispose() { if (Directory.Exists(_root)) Directory.Delete(_root, true); }

    private static void WriteBoxValue(byte[] bytes, ReadOnlySpan<byte> type, int offset, uint value)
    {
        var at = bytes.AsSpan().IndexOf(type) + 4 + offset;
        System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(at, 4), value);
    }
}
