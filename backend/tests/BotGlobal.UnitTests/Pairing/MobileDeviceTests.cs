using BotGlobal.Pairing.Domain;

namespace BotGlobal.UnitTests.Pairing;

public sealed class MobileDeviceTests
{
    [Fact]
    public void RotateCredential_ReactivatesRevokedDevice()
    {
        var now = DateTimeOffset.UtcNow;

        var device = new MobileDevice(
            Guid.NewGuid(),
            Guid.NewGuid(),
            "connect-user-test-84621",
            "installation-1",
            "android",
            "Samsung",
            "1.0.1",
            new byte[32],
            now);

        device.Revoke(now.AddMinutes(1));

        device.RotateCredential(
            Enumerable.Repeat((byte)1, 32).ToArray(),
            "connect-user-test-84621",
            "android",
            "Samsung A21s",
            "1.0.2",
            now.AddMinutes(2));

        Assert.True(device.IsActive);
        Assert.Null(device.RevokedAtUtc);
        Assert.Equal(
            "Samsung A21s",
            device.DeviceName);
    }
}
