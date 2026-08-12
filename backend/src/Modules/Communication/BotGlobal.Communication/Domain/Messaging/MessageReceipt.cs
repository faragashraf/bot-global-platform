using BotGlobal.Communication.Domain.Identity;
namespace BotGlobal.Communication.Domain.Messaging;

public sealed class MessageReceipt
{
    private MessageReceipt()
    {
    }

    private MessageReceipt(
        Guid messageId,
        string userId)
    {
        if (messageId == Guid.Empty)
        {
            throw new ArgumentException(
                "Message id is required.",
                nameof(messageId));
        }

        MessageId = messageId;
        UserId = ExternalUserId.Normalize(
            userId,
            nameof(userId));
    }

    public Guid MessageId { get; private set; }

    public string UserId { get; private set; } = string.Empty;

    public DateTimeOffset? DeliveredAtUtc { get; private set; }

    public DateTimeOffset? ReadAtUtc { get; private set; }

    public static MessageReceipt Create(
        Guid messageId,
        string userId)
    {
        return new MessageReceipt(
            messageId,
            userId);
    }

    public void MarkDelivered(DateTimeOffset deliveredAtUtc)
    {
        if (DeliveredAtUtc is null
            || deliveredAtUtc < DeliveredAtUtc.Value)
        {
            DeliveredAtUtc = deliveredAtUtc;
        }
    }

    public void MarkRead(DateTimeOffset readAtUtc)
    {
        if (DeliveredAtUtc is null)
        {
            DeliveredAtUtc = readAtUtc;
        }

        if (readAtUtc < DeliveredAtUtc.Value)
        {
            throw new ArgumentOutOfRangeException(
                nameof(readAtUtc),
                "Read time cannot predate delivery.");
        }

        if (ReadAtUtc is null
            || readAtUtc < ReadAtUtc.Value)
        {
            ReadAtUtc = readAtUtc;
        }
    }
}
