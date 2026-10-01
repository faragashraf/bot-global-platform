using SentriCam.Domain.Common;
using SentriCam.Domain.Devices;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Domain;

public sealed class DeviceAggregateTests
{
    [Fact]
    public void RegisterCreatesADeviceWithNormalizedCapabilities()
    {
        var device = Device.Register(
            "  Front Door  ",
            DeviceTestFactory.CreateIdentity(),
            [new DeviceCapabilityDefinition(" camera ", "1", true)],
            DeviceTestFactory.Now);

        Assert.NotEqual(Guid.Empty, device.Id.Value);
        Assert.Equal("Front Door", device.DisplayName);
        Assert.Equal(DeviceStatus.Registered, device.Status);
        var capability = Assert.Single(device.Capabilities);
        Assert.Equal("CAMERA", capability.NormalizedName);
        Assert.True(capability.IsEnabled);
    }

    [Fact]
    public void RegisterRejectsDuplicateCapabilityNames()
    {
        var exception = Assert.Throws<DomainValidationException>(() => Device.Register(
            "Front Door",
            DeviceTestFactory.CreateIdentity(),
            [
                new DeviceCapabilityDefinition("Camera", "1", true),
                new DeviceCapabilityDefinition("camera", "2", true),
            ],
            DeviceTestFactory.Now));

        Assert.Contains("duplicated", exception.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void RefreshRegistrationUpdatesAndDisablesCapabilitiesThatAreNoLongerAdvertised()
    {
        var device = Device.Register(
            "Front Door",
            DeviceTestFactory.CreateIdentity(),
            [
                new DeviceCapabilityDefinition("Camera", "1", true),
                new DeviceCapabilityDefinition("Microphone", "1", true),
            ],
            DeviceTestFactory.Now);
        var updatedAt = DeviceTestFactory.Now.AddMinutes(5);

        device.RefreshRegistration(
            "Entry Camera",
            DeviceIdentity.Create(
                "installation-001",
                "Google",
                "Pixel 9",
                "Android",
                "16",
                "1.1.0"),
            [new DeviceCapabilityDefinition("Camera", "2", true)],
            updatedAt);

        Assert.Equal("Entry Camera", device.DisplayName);
        Assert.Equal("1.1.0", device.Identity.AppVersion);
        Assert.Equal("2", device.Capabilities.Single(item => item.NormalizedName == "CAMERA").Version);
        Assert.False(device.Capabilities.Single(item => item.NormalizedName == "MICROPHONE").IsEnabled);
        Assert.Equal(updatedAt, device.UpdatedAtUtc);
    }

    [Fact]
    public void MarkSeenMovesARegisteredDeviceOnline()
    {
        var device = DeviceTestFactory.CreateDevice();
        var pingAt = DeviceTestFactory.Now.AddMinutes(1);

        device.MarkSeen(pingAt);

        Assert.Equal(DeviceStatus.Online, device.Status);
        Assert.Equal(pingAt, device.LastSeenAtUtc);
    }

    [Fact]
    public void DisconnectedConnectionRejectsFurtherHeartbeats()
    {
        var connection = DeviceConnection.Connect(
            DeviceTestFactory.CreateDevice().Id,
            "signalr-connection-1",
            DeviceTestFactory.Now);
        connection.Disconnect(DeviceTestFactory.Now.AddMinutes(1));

        Assert.Throws<DomainValidationException>(() =>
            connection.Heartbeat(DeviceTestFactory.Now.AddMinutes(2)));
    }
}
