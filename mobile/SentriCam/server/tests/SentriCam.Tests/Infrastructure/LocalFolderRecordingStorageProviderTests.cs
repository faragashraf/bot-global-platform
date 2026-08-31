using System.Security.Cryptography;
using Microsoft.Extensions.Options;
using SentriCam.Application.Common;
using SentriCam.Application.Recordings;
using SentriCam.Infrastructure.Storage;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Infrastructure;

public sealed class LocalFolderRecordingStorageProviderTests : IDisposable
{
    private readonly string _root = Path.Combine(
        Path.GetTempPath(),
        $"sentricam-recording-storage-{Guid.NewGuid():N}");

    [Fact]
    public async Task SaveStreamsContentComputesChecksumAndCanReadItBack()
    {
        var provider = CreateProvider(1_024);
        var content = new byte[] { 4, 3, 2, 1 };
        var request = CreateRequest();

        var stored = await provider.SaveRecordingAsync(
            request,
            new MemoryStream(content),
            TestContext.Current.CancellationToken);
        await using var opened = await provider.OpenReadAsync(
            stored.RelativePath,
            TestContext.Current.CancellationToken);
        using var copy = new MemoryStream();
        await opened.CopyToAsync(copy, TestContext.Current.CancellationToken);

        Assert.Equal(content, copy.ToArray());
        Assert.Equal(
            Convert.ToHexString(SHA256.HashData(content)).ToLowerInvariant(),
            stored.ChecksumSha256);
        Assert.DoesNotContain(".uploading-", stored.RelativePath, StringComparison.Ordinal);
    }

    [Fact]
    public async Task SaveRejectsOversizedContentAndLeavesNoPartialObject()
    {
        var provider = CreateProvider(3);

        await Assert.ThrowsAsync<PayloadTooLargeException>(() => provider.SaveRecordingAsync(
            CreateRequest(),
            new MemoryStream([1, 2, 3, 4]),
            TestContext.Current.CancellationToken));

        Assert.Empty(Directory.EnumerateFiles(_root, "*", SearchOption.AllDirectories));
    }

    [Fact]
    public async Task SaveStreamsMultiMegabyteContentWithoutRequiringASeekableSource()
    {
        const int size = 8 * 1024 * 1024;
        var provider = CreateProvider(size + 1);
        await using var content = new DeterministicNonSeekableStream(size);

        var stored = await provider.SaveRecordingAsync(
            CreateRequest(),
            content,
            TestContext.Current.CancellationToken);

        Assert.Equal(size, stored.SizeBytes);
        Assert.Equal(64, stored.ChecksumSha256.Length);
        Assert.True(File.Exists(Path.Combine(_root, stored.RelativePath)));
    }

    [Fact]
    public async Task StorageRejectsTraversalKeys()
    {
        var provider = CreateProvider(1_024);
        await Assert.ThrowsAsync<ResourceConflictException>(() =>
            provider.OpenReadAsync("../outside.mp4", TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task ThumbnailGeneratorReportsClearFailureWithoutConfiguredFfmpeg()
    {
        var provider = CreateProvider(1_024);
        var options = Options.Create(new RecordingStorageOptions
        {
            RootPath = _root,
            MaximumUploadBytes = 1_024,
            FfmpegPath = string.Empty,
        });
        var generator = new LocalRecordingThumbnailGenerator(provider, options);

        var failure = await Assert.ThrowsAsync<RecordingThumbnailGenerationException>(() => generator.GenerateAsync(
            new RecordingThumbnailRequest(
                Guid.NewGuid(),
                "recording.mp4",
                DeviceTestFactory.Now,
                65_000,
                Motion: true),
            TestContext.Current.CancellationToken));

        Assert.Equal("ffmpeg_not_configured", failure.ErrorCode);
    }

    [Fact]
    public void ThumbnailGeneratorUsesArgumentListForUntrustedPathsAndSeeksAfterFirstSecond()
    {
        var provider = CreateProvider(1_024);
        var generator = new LocalRecordingThumbnailGenerator(
            provider,
            Options.Create(new RecordingStorageOptions
            {
                RootPath = _root,
                MaximumUploadBytes = 1_024,
                FfmpegPath = "/media tools/ffmpeg",
            }));
        const string unsafeLookingSource = "/tmp/camera;$(touch injected).mp4";
        const string unsafeLookingOutput = "/tmp/output name;rm.jpg";

        using var process = generator.CreateProcess(
            new RecordingThumbnailRequest(Guid.NewGuid(), "unused.mp4", DeviceTestFactory.Now, 60_000, true),
            unsafeLookingSource,
            unsafeLookingOutput);

        Assert.Equal("/media tools/ffmpeg", process.StartInfo.FileName);
        Assert.Contains(unsafeLookingSource, process.StartInfo.ArgumentList);
        Assert.Contains(unsafeLookingOutput, process.StartInfo.ArgumentList);
        Assert.Empty(process.StartInfo.Arguments);
        var seekIndex = process.StartInfo.ArgumentList.IndexOf("-ss");
        Assert.Equal("6", process.StartInfo.ArgumentList[seekIndex + 1]);
    }

    [Fact]
    public async Task ThumbnailGeneratorReturnsJpegFromConfiguredMediaProcessor()
    {
        if (OperatingSystem.IsWindows()) return;
        Directory.CreateDirectory(_root);
        var source = Path.Combine(_root, "recording.mp4");
        await File.WriteAllBytesAsync(source, [1, 2, 3], TestContext.Current.CancellationToken);
        var executable = Path.Combine(_root, "fake-ffmpeg.sh");
        await File.WriteAllTextAsync(
            executable,
            "#!/bin/sh\nfor last do :; done\nprintf '\\377\\330\\377\\331' > \"$last\"\n",
            TestContext.Current.CancellationToken);
        File.SetUnixFileMode(executable, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        var provider = CreateProvider(1_024);
        var generator = new LocalRecordingThumbnailGenerator(
            provider,
            Options.Create(new RecordingStorageOptions
            {
                RootPath = _root,
                MaximumUploadBytes = 1_024,
                FfmpegPath = executable,
            }));

        var thumbnail = await generator.GenerateAsync(
            new RecordingThumbnailRequest(Guid.NewGuid(), "recording.mp4", DeviceTestFactory.Now, 10_000, true),
            TestContext.Current.CancellationToken);

        Assert.Equal(".jpg", thumbnail.Extension);
        Assert.Equal("image/jpeg", thumbnail.ContentType);
        Assert.Equal([0xff, 0xd8, 0xff, 0xd9], thumbnail.Content);
    }

    public void Dispose()
    {
        if (Directory.Exists(_root))
        {
            Directory.Delete(_root, recursive: true);
        }
        GC.SuppressFinalize(this);
    }

    private LocalFolderRecordingStorageProvider CreateProvider(long maximumBytes) => new(
        Options.Create(new RecordingStorageOptions
        {
            RootPath = _root,
            MaximumUploadBytes = maximumBytes,
        }));

    private static RecordingStorageWriteRequest CreateRequest() => new(
        Guid.Parse("11111111-1111-1111-1111-111111111111"),
        Guid.Parse("22222222-2222-2222-2222-222222222222"),
        "capture.mp4",
        DeviceTestFactory.Now);

    private sealed class DeterministicNonSeekableStream(long remaining) : Stream
    {
        private long _remaining = remaining;
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count)
        {
            var read = (int)Math.Min(count, _remaining);
            if (read == 0) return 0;
            Array.Fill(buffer, (byte)0x5a, offset, read);
            _remaining -= read;
            return read;
        }
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var read = (int)Math.Min(buffer.Length, _remaining);
            if (read == 0) return ValueTask.FromResult(0);
            buffer.Span[..read].Fill(0x5a);
            _remaining -= read;
            return ValueTask.FromResult(read);
        }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
