using BotGlobal.Contracts.Mobile;
using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Communication.Contracts.MobileNotifications;

namespace BotGlobal.UnitTests.Communication;

public sealed class MobileNotificationServiceTests
{
    [Fact]
    public async Task SendAsync_UsesAuthenticatedPlatformAndExternalSubject()
    {
        var platformClientId = Guid.NewGuid();

        var resolver =
            new RecordingResolver(
                [
                    new MobileRecipientDevice(
                        Guid.NewGuid(),
                        "installation-1",
                        "android",
                        "Samsung SM-A217F")
                ]);

        var delivery = new RecordingDelivery();

        var service =
            new MobileNotificationService(
                resolver,
                delivery,
                TimeProvider.System);

        var result =
            await service.SendAsync(
                platformClientId,
                CreateRequest("84621"),
                CancellationToken.None);

        Assert.Equal(
            platformClientId,
            resolver.PlatformClientId);

        Assert.Equal(
            "84621",
            resolver.ExternalSubjectId);

        Assert.Equal(1, result.ActiveDeviceCount);
        Assert.Equal("accepted", result.Status);

        Assert.NotNull(delivery.Notification);
        Assert.Equal(
            "84621",
            delivery.Notification!.RecipientExternalSubjectId);

        Assert.Single(delivery.Devices);
    }

    [Fact]
    public async Task SendAsync_ReturnsNoActiveDevices_WhenNoneResolve()
    {
        var delivery = new RecordingDelivery();

        var service =
            new MobileNotificationService(
                new RecordingResolver([]),
                delivery,
                TimeProvider.System);

        var result =
            await service.SendAsync(
                Guid.NewGuid(),
                CreateRequest("84621"),
                CancellationToken.None);

        Assert.Equal(0, result.ActiveDeviceCount);
        Assert.Equal(
            "no-active-devices",
            result.Status);

        Assert.Empty(delivery.Devices);
    }

    [Fact]
    public async Task SendAsync_TrimsRecipientSubject()
    {
        var resolver =
            new RecordingResolver([]);

        var service =
            new MobileNotificationService(
                resolver,
                new RecordingDelivery(),
                TimeProvider.System);

        await service.SendAsync(
            Guid.NewGuid(),
            CreateRequest("  84621  "),
            CancellationToken.None);

        Assert.Equal(
            "84621",
            resolver.ExternalSubjectId);
    }

    private static SendMobileNotificationRequest CreateRequest(
        string subjectId) =>
        new(
            subjectId,
            "إشعار تجريبي",
            "Test notification",
            "هذه أول رسالة حقيقية",
            "This is the first real message",
            "general",
            MobileNotificationPriority.Normal);

    private sealed class RecordingResolver(
        IReadOnlyList<MobileRecipientDevice> devices)
        : IMobileRecipientResolver
    {
        public Guid? PlatformClientId { get; private set; }

        public string? ExternalSubjectId { get; private set; }

        public Task<IReadOnlyList<MobileRecipientDevice>>
            ResolveActiveDevicesAsync(
                Guid platformClientId,
                string externalSubjectId,
                CancellationToken cancellationToken)
        {
            PlatformClientId = platformClientId;
            ExternalSubjectId = externalSubjectId;

            return Task.FromResult(devices);
        }
    }

    private sealed class RecordingDelivery
        : IMobileNotificationDelivery
    {
        public MobileNotificationEnvelope? Notification {
            get;
            private set;
        }

        public IReadOnlyList<MobileRecipientDevice> Devices {
            get;
            private set;
        } = [];

        public Task<MobileNotificationDeliveryResult> DeliverAsync(
            MobileNotificationEnvelope notification,
            IReadOnlyList<MobileRecipientDevice> devices,
            CancellationToken cancellationToken)
        {
            Notification = notification;
            Devices = devices;

            return Task.FromResult(
                new MobileNotificationDeliveryResult(
                    AttemptedDeviceCount: devices.Count,
                    DeliveredDeviceCount: 0,
                    SignalRDeliveredDeviceCount: 0,
                    FcmDeliveredDeviceCount: 0));
        }
    }
}
