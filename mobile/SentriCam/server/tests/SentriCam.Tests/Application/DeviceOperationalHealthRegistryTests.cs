using System.Text.Json;
using SentriCam.Application.Monitoring;
using SentriCam.Contracts.Monitoring;
using SentriCam.Domain.Devices;

namespace SentriCam.Tests.Application;

public sealed class DeviceOperationalHealthRegistryTests
{
    [Fact]
    public void AuthenticatedDeviceCanPublishStableAndroidHealthPayload()
    {
        var deviceId = DeviceId.New();
        using var document = JsonDocument.Parse("""
            {
              "overall": "RECOVERING",
              "realtime": {
                "subsystem": "REALTIME",
                "lifecycle": "RECOVERING",
                "health": "RECOVERING",
                "recoveryReason": "network_offline",
                "reconnectCount": 2
              },
              "schemaVersion": 1
            }
            """);
        var registry = new DeviceOperationalHealthRegistry();

        registry.Report(deviceId, new DeviceOperationalHealthReport(
            deviceId.Value,
            document.RootElement.Clone(),
            DateTimeOffset.UtcNow));

        var current = Assert.IsType<DeviceOperationalHealthReport>(registry.GetLatest(deviceId));
        Assert.Equal("RECOVERING", current.Health.GetProperty("overall").GetString());
        Assert.Equal(2, current.Health.GetProperty("realtime").GetProperty("reconnectCount").GetInt32());
    }

    [Fact]
    public void DeviceCannotPublishHealthForAnotherIdentity()
    {
        var authenticated = DeviceId.New();
        using var document = JsonDocument.Parse("{}");
        var registry = new DeviceOperationalHealthRegistry();

        Assert.Throws<InvalidOperationException>(() => registry.Report(
            authenticated,
            new DeviceOperationalHealthReport(
                Guid.NewGuid(),
                document.RootElement.Clone(),
                DateTimeOffset.UtcNow)));
    }
}
