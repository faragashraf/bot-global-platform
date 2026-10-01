using SentriCam.Domain.Common;
using SentriCam.Domain.Recordings;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Domain;

public sealed class RecordingTests
{
    [Fact]
    public void RecordingPersistsRequiredUploadMetadata()
    {
        var device = DeviceTestFactory.CreateDevice();
        var recording = Recording.Create(
            Guid.NewGuid(),
            device.Id,
            "segment-1",
            "session-1",
            "capture.mp4",
            "video/mp4",
            12_000,
            512,
            DeviceTestFactory.Now.AddMinutes(-1),
            DeviceTestFactory.Now,
            isMotion: true,
            isManual: false,
            new string('a', 64),
            "device/2026/recording.mp4");

        Assert.True(recording.TryBeginThumbnailGeneration(DeviceTestFactory.Now));
        recording.CompleteThumbnailGeneration("device/2026/recording.jpg", DeviceTestFactory.Now);

        Assert.Equal(device.Id, recording.DeviceId);
        Assert.Equal(12_000, recording.DurationMilliseconds);
        Assert.True(recording.IsMotion);
        Assert.False(recording.IsManual);
        Assert.Equal("device/2026/recording.jpg", recording.ThumbnailRelativePath);
        Assert.Equal(ThumbnailGenerationState.Ready, recording.ThumbnailState);
    }

    [Fact]
    public void RecordingRejectsAmbiguousTriggerAndInvalidChecksum()
    {
        var device = DeviceTestFactory.CreateDevice();

        Assert.Throws<DomainValidationException>(() => Recording.Create(
            Guid.NewGuid(),
            device.Id,
            "segment-1",
            "session-1",
            "capture.mp4",
            "video/mp4",
            1,
            1,
            DeviceTestFactory.Now,
            DeviceTestFactory.Now,
            isMotion: false,
            isManual: false,
            "not-a-checksum",
            "recording.mp4"));
    }
}
