using BotGlobal.Communication.Domain.Identity;
namespace BotGlobal.Communication.Domain.Messaging;

public enum CommunicationMessageKind
{
    Text = 1,
    Link = 2,
    Image = 3,
    Video = 4,
    Voice = 5,
    File = 6
}

public sealed class Message
{
    public const int ClientMessageIdMaxLength = 100;
    public const int TextMaxLength = 4000;
    public const int UrlMaxLength = 2048;

    private readonly List<MessageReceipt> _receipts = [];

    private Message()
    {
    }

    private Message(
        Guid id,
        Guid conversationId,
        string senderUserId,
        string clientMessageId,
        CommunicationMessageKind kind,
        string? textContent,
        string? url,
        DateTimeOffset createdAtUtc)
    {
        Id = RequireGuid(id, nameof(id));
        ConversationId = RequireGuid(
            conversationId,
            nameof(conversationId));
        SenderUserId = ExternalUserId.Normalize(
            senderUserId,
            nameof(senderUserId));
        ClientMessageId = NormalizeClientMessageId(
            clientMessageId);
        Kind = kind;
        TextContent = textContent;
        Url = url;
        CreatedAtUtc = createdAtUtc;
    }

    public Guid Id { get; private set; }

    public Guid ConversationId { get; private set; }

    public string SenderUserId { get; private set; } = string.Empty;

    public string ClientMessageId { get; private set; } = string.Empty;

    public long SequenceNumber { get; private set; }

    public CommunicationMessageKind Kind { get; private set; }

    public string? TextContent { get; private set; }

    public string? Url { get; private set; }

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public IReadOnlyCollection<MessageReceipt> Receipts => _receipts;

    public static Message CreateText(
        Guid conversationId,
        string senderUserId,
        string clientMessageId,
        string text,
        DateTimeOffset createdAtUtc)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(text);

        var normalized = text.Trim();

        if (normalized.Length > TextMaxLength)
        {
            throw new ArgumentException(
                $"Text cannot exceed {TextMaxLength} characters.",
                nameof(text));
        }

        return new Message(
            Guid.NewGuid(),
            conversationId,
            senderUserId,
            clientMessageId,
            CommunicationMessageKind.Text,
            normalized,
            url: null,
            createdAtUtc);
    }

    public static Message CreateLink(
        Guid conversationId,
        string senderUserId,
        string clientMessageId,
        string url,
        string? caption,
        DateTimeOffset createdAtUtc)
    {
        var normalizedUrl = NormalizeUrl(url);
        var normalizedCaption = NormalizeOptionalText(caption);

        return new Message(
            Guid.NewGuid(),
            conversationId,
            senderUserId,
            clientMessageId,
            CommunicationMessageKind.Link,
            normalizedCaption,
            normalizedUrl,
            createdAtUtc);
    }

    private static string NormalizeClientMessageId(
        string clientMessageId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(
            clientMessageId);

        var normalized = clientMessageId.Trim();

        if (normalized.Length > ClientMessageIdMaxLength)
        {
            throw new ArgumentException(
                $"Client message id cannot exceed {ClientMessageIdMaxLength} characters.",
                nameof(clientMessageId));
        }

        return normalized;
    }

    private static string NormalizeUrl(string url)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(url);

        var normalized = url.Trim();

        if (normalized.Length > UrlMaxLength)
        {
            throw new ArgumentException(
                $"URL cannot exceed {UrlMaxLength} characters.",
                nameof(url));
        }

        if (!Uri.TryCreate(
                normalized,
                UriKind.Absolute,
                out var parsed)
            || (parsed.Scheme != Uri.UriSchemeHttp
                && parsed.Scheme != Uri.UriSchemeHttps))
        {
            throw new ArgumentException(
                "URL must be an absolute HTTP(S) URL.",
                nameof(url));
        }

        return normalized;
    }

    private static string? NormalizeOptionalText(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var normalized = value.Trim();

        if (normalized.Length > TextMaxLength)
        {
            throw new ArgumentException(
                $"Text cannot exceed {TextMaxLength} characters.",
                nameof(value));
        }

        return normalized;
    }

    private static Guid RequireGuid(Guid value, string name)
    {
        if (value == Guid.Empty)
        {
            throw new ArgumentException(
                $"{name} is required.",
                name);
        }

        return value;
    }
}
