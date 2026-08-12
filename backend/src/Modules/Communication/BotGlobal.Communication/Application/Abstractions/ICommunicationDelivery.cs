namespace BotGlobal.Communication.Application.Abstractions;

public sealed record RealtimeTestMessage(
    string DeliveryId,
    string SenderUserId,
    string TargetUserId,
    string Text,
    DateTimeOffset SentAtUtc);

public interface ICommunicationDelivery
{
    Task<RealtimeTestMessage> SendTestMessageToUserAsync(
        string senderUserId,
        string targetUserId,
        string text,
        CancellationToken cancellationToken = default);
}
