using SentriCam.Domain.Common;
using SentriCam.Domain.Devices;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Domain;

public sealed class SnapshotVersionTests
{
    [Fact]
    public void LegacyCaptureDefaultsToVersionOneWithoutChangingCapturedTime()
    {
        var device = DeviceTestFactory.CreateDevice();

        var snapshot = DeviceSnapshot.Capture(
            device.Id,
            DeviceStatus.Online,
            80,
            1_000,
            false,
            false,
            null,
            DeviceTestFactory.Now);

        Assert.Equal(1, snapshot.SnapshotVersion);
        Assert.Equal(1, snapshot.SchemaVersion);
        Assert.Equal(DeviceTestFactory.Now, snapshot.GeneratedAtUtc);
        Assert.Equal(DeviceTestFactory.Now, snapshot.CapturedAtUtc);
    }

    [Fact]
    public void VersionedCapturePreservesClientSequenceAndSchema()
    {
        var device = DeviceTestFactory.CreateDevice();
        var generatedAt = DeviceTestFactory.Now.AddSeconds(-2);

        var snapshot = DeviceSnapshot.Capture(
            device.Id,
            DeviceStatus.Monitoring,
            75,
            2_000,
            true,
            false,
            "{}",
            42,
            3,
            generatedAt,
            DeviceTestFactory.Now);

        Assert.Equal(42, snapshot.SnapshotVersion);
        Assert.Equal(3, snapshot.SchemaVersion);
        Assert.Equal(generatedAt, snapshot.GeneratedAtUtc);
    }

    [Fact]
    public void SnapshotRejectsInvalidVersions()
    {
        var device = DeviceTestFactory.CreateDevice();

        Assert.Throws<DomainValidationException>(() => DeviceSnapshot.Capture(
            device.Id,
            DeviceStatus.Online,
            null,
            null,
            false,
            false,
            null,
            0,
            1,
            DeviceTestFactory.Now,
            DeviceTestFactory.Now));
    }
}
