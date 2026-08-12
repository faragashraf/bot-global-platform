namespace BotGlobal.Communication.Contracts.Messaging;

public enum MessageKind
{
    Text = 1,
    Link = 2,
    Image = 3,
    Video = 4,
    Voice = 5,
    File = 6
}

public sealed record SendTextMessageRequest(
    string ConversationId,
    string ClientMessageId,
    string Text);

public sealed record SendLinkMessageRequest(
    string ConversationId,
    string ClientMessageId,
    string Url,
    string? Caption);

public sealed record MessageEnvelope(
    string MessageId,
    string ConversationId,
    string SenderUserId,
    string ClientMessageId,
    MessageKind Kind,
    string? Text,
    string? Url,
    DateTimeOffset SentAtUtc);

public sealed record MessageReceiptRequest(
    string ConversationId,
    string MessageId);

public sealed record MessageDeliveredEvent(
    string ConversationId,
    string MessageId,
    string UserId,
    DateTimeOffset DeliveredAtUtc);

public sealed record MessageReadEvent(
    string ConversationId,
    string MessageId,
    string UserId,
    DateTimeOffset ReadAtUtc);

public sealed record TypingChangedEvent(
    string ConversationId,
    string UserId,
    bool IsTyping,
    DateTimeOffset ChangedAtUtc);
