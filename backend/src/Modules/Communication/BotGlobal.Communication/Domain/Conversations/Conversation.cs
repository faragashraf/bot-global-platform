using BotGlobal.Communication.Domain.Identity;
namespace BotGlobal.Communication.Domain.Conversations;

public enum ConversationType
{
    Direct = 1,
    Group = 2
}

public sealed class Conversation
{
    public const int GroupTitleMaxLength = 200;
    public const int DirectKeyMaxLength = (ExternalUserId.MaxLength * 2) + 1;

    private readonly List<ConversationParticipant> _participants = [];

    private Conversation()
    {
    }

    private Conversation(
        Guid id,
        ConversationType type,
        string createdByUserId,
        string? title,
        string? directKey,
        DateTimeOffset createdAtUtc)
    {
        Id = RequireId(id, nameof(id));
        Type = type;
        CreatedByUserId = ExternalUserId.Normalize(
            createdByUserId,
            nameof(createdByUserId));
        Title = title;
        DirectKey = directKey;
        CreatedAtUtc = createdAtUtc;
        LastActivityAtUtc = createdAtUtc;
    }

    public Guid Id { get; private set; }

    public ConversationType Type { get; private set; }

    public string? Title { get; private set; }

    public string? DirectKey { get; private set; }

    public string CreatedByUserId { get; private set; } = string.Empty;

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public DateTimeOffset LastActivityAtUtc { get; private set; }

    public IReadOnlyCollection<ConversationParticipant> Participants =>
        _participants;

    public static Conversation CreateDirect(
        string initiatorUserId,
        string otherUserId,
        DateTimeOffset createdAtUtc)
    {
        var initiator = ExternalUserId.Normalize(
            initiatorUserId,
            nameof(initiatorUserId));
        var other = ExternalUserId.Normalize(
            otherUserId,
            nameof(otherUserId));

        if (string.Equals(
                initiator,
                other,
                StringComparison.Ordinal))
        {
            throw new ArgumentException(
                "A direct conversation requires two different users.",
                nameof(otherUserId));
        }

        var conversation = new Conversation(
            Guid.NewGuid(),
            ConversationType.Direct,
            initiator,
            title: null,
            DirectConversationKey.Create(
                initiator,
                other),
            createdAtUtc);

        conversation._participants.Add(
            ConversationParticipant.Join(
                conversation.Id,
                initiator,
                ConversationParticipantRole.Member,
                createdAtUtc));

        conversation._participants.Add(
            ConversationParticipant.Join(
                conversation.Id,
                other,
                ConversationParticipantRole.Member,
                createdAtUtc));

        return conversation;
    }

    public static Conversation CreateGroup(
        string ownerUserId,
        string title,
        DateTimeOffset createdAtUtc)
    {
        var owner = ExternalUserId.Normalize(
            ownerUserId,
            nameof(ownerUserId));

        var normalizedTitle = NormalizeTitle(title);

        var conversation = new Conversation(
            Guid.NewGuid(),
            ConversationType.Group,
            owner,
            normalizedTitle,
            directKey: null,
            createdAtUtc);

        conversation._participants.Add(
            ConversationParticipant.Join(
                conversation.Id,
                owner,
                ConversationParticipantRole.Owner,
                createdAtUtc));

        return conversation;
    }

    public void RenameGroup(string title)
    {
        EnsureGroup();
        Title = NormalizeTitle(title);
    }

    public void AddParticipant(
        string userId,
        DateTimeOffset joinedAtUtc)
    {
        EnsureGroup();

        var normalizedUserId = ExternalUserId.Normalize(
            userId,
            nameof(userId));

        var existing = _participants
            .SingleOrDefault(participant =>
                string.Equals(
                    participant.UserId,
                    normalizedUserId,
                    StringComparison.Ordinal));

        if (existing is null)
        {
            _participants.Add(
                ConversationParticipant.Join(
                    Id,
                    normalizedUserId,
                    ConversationParticipantRole.Member,
                    joinedAtUtc));

            return;
        }

        if (existing.IsActive)
        {
            throw new InvalidOperationException(
                "The user is already an active participant.");
        }

        existing.Rejoin(
            ConversationParticipantRole.Member,
            joinedAtUtc);
    }

    public void RemoveParticipant(
        string userId,
        DateTimeOffset leftAtUtc)
    {
        EnsureGroup();

        var normalizedUserId = ExternalUserId.Normalize(
            userId,
            nameof(userId));

        var participant = _participants
            .SingleOrDefault(item =>
                string.Equals(
                    item.UserId,
                    normalizedUserId,
                    StringComparison.Ordinal))
            ?? throw new InvalidOperationException(
                "The user is not a conversation participant.");

        if (participant.Role == ConversationParticipantRole.Owner)
        {
            throw new InvalidOperationException(
                "The group owner cannot leave before ownership is transferred.");
        }

        participant.Leave(leftAtUtc);
    }

    public void MarkActivity(DateTimeOffset activityAtUtc)
    {
        if (activityAtUtc < CreatedAtUtc)
        {
            throw new ArgumentOutOfRangeException(
                nameof(activityAtUtc),
                "Conversation activity cannot predate creation.");
        }

        if (activityAtUtc > LastActivityAtUtc)
        {
            LastActivityAtUtc = activityAtUtc;
        }
    }

    private void EnsureGroup()
    {
        if (Type != ConversationType.Group)
        {
            throw new InvalidOperationException(
                "Participant management is only supported for group conversations.");
        }
    }

    private static string NormalizeTitle(string title)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(title);

        var normalized = title.Trim();

        if (normalized.Length > GroupTitleMaxLength)
        {
            throw new ArgumentException(
                $"Group title cannot exceed {GroupTitleMaxLength} characters.",
                nameof(title));
        }

        return normalized;
    }

    private static Guid RequireId(Guid value, string name)
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

public static class DirectConversationKey
{
    public static string Create(
        string firstUserId,
        string secondUserId)
    {
        var first = ExternalUserId.Normalize(
            firstUserId,
            nameof(firstUserId));
        var second = ExternalUserId.Normalize(
            secondUserId,
            nameof(secondUserId));

        if (string.Equals(
                first,
                second,
                StringComparison.Ordinal))
        {
            throw new ArgumentException(
                "Direct-conversation users must be different.",
                nameof(secondUserId));
        }

        return string.CompareOrdinal(first, second) < 0
            ? $"{first}:{second}"
            : $"{second}:{first}";
    }
}
