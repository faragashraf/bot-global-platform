using BotGlobal.Communication.Application.Abstractions;
using BotGlobal.Communication.Domain.Identity;
using BotGlobal.Communication.Hubs;
using Microsoft.AspNetCore.SignalR;

namespace BotGlobal.Communication.Application.Delivery;

internal sealed class SignalRCommunicationDelivery(
    IHubContext<CommunicationHub, ICommunicationClient> hubContext)
    : ICommunicationDelivery
{
    public async Task<RealtimeTestMessage> SendTestMessageToUserAsync(
        string senderUserId,
        string targetUserId,
        string text,
        CancellationToken cancellationToken = default)
    {
        var sender = ExternalUserId.Normalize(
            senderUserId,
            nameof(senderUserId));

        var target = ExternalUserId.Normalize(
            targetUserId,
            nameof(targetUserId));

        ArgumentException.ThrowIfNullOrWhiteSpace(text);

        var normalizedText = text.Trim();

        if (normalizedText.Length > 1000)
        {
            throw new ArgumentException(
                "Realtime test message cannot exceed 1000 characters.",
                nameof(text));
        }

        var message = new RealtimeTestMessage(
            Guid.NewGuid().ToString("N"),
            sender,
            target,
            normalizedText,
            DateTimeOffset.UtcNow);

        await hubContext
            .Clients
            .User(target)
            .RealtimeTestMessageReceived(message);

        return message;
    }
}
