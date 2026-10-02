using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Communication.Contracts.MobileNotifications;

namespace BotGlobal.UnitTests.Communication;

public sealed class IncomingCallPushExpiryTests
{
    [Fact]
    public void Incoming_call_push_expires_with_ring_window()
    {
        var now = new DateTimeOffset(2026, 10, 1, 18, 0, 0, TimeSpan.Zero);
        var notification = Message("incoming_call", now.AddSeconds(45).ToString("O"));

        var ttl = CompositeMobileNotificationDelivery.SelectPushTimeToLive(
            notification, TimeSpan.FromDays(7), now);

        Assert.Equal(TimeSpan.FromSeconds(45), ttl);
    }

    [Fact]
    public void Expired_call_offer_is_not_sent_to_fcm()
    {
        var now = new DateTimeOffset(2026, 10, 1, 18, 0, 0, TimeSpan.Zero);
        Assert.Null(CompositeMobileNotificationDelivery.SelectPushTimeToLive(
            Message("incoming_call", now.AddSeconds(-1).ToString("O")),
            TimeSpan.FromDays(7), now));
    }

    [Fact]
    public void Other_notification_types_keep_existing_ttl()
    {
        var now = new DateTimeOffset(2026, 10, 1, 18, 0, 0, TimeSpan.Zero);
        var fallback = TimeSpan.FromDays(7);

        Assert.Equal(fallback, CompositeMobileNotificationDelivery.SelectPushTimeToLive(
            Message("chat_message", now.AddSeconds(-1).ToString("O")), fallback, now));
    }

    private static MobileNotificationEnvelope Message(string type, string expiry) =>
        new(
            "notification-1", "subject-1", "عنوان", "Title", "المحتوى", "Body",
            type, MobileNotificationPriority.High, DateTimeOffset.UtcNow,
            new Dictionary<string, string> { ["expiresAtUtc"] = expiry });
}
