using BotGlobal.Communication.Domain.Identity;
namespace BotGlobal.Communication.Domain.Conversations;

public enum ConversationParticipantRole
{
    Member = 1,
    Admin = 2,
    Owner = 3
}

public sealed class ConversationParticipant
{
    private ConversationParticipant()
    {
    }

    private ConversationParticipant(
        Guid conversationId,
        string userId,
        ConversationParticipantRole role,
        DateTimeOffset joinedAtUtc)
    {
        if (conversationId == Guid.Empty)
        {
            throw new ArgumentException(
                "Conversation id is required.",
                nameof(conversationId));
        }

        ConversationId = conversationId;
        UserId = ExternalUserId.Normalize(
            userId,
            nameof(userId));
        Role = role;
        JoinedAtUtc = joinedAtUtc;
    }

    public Guid ConversationId { get; private set; }

    public string UserId { get; private set; } = string.Empty;

    public ConversationParticipantRole Role { get; private set; }

    public DateTimeOffset JoinedAtUtc { get; private set; }

    public DateTimeOffset? LeftAtUtc { get; private set; }

    public bool IsActive => LeftAtUtc is null;

    internal static ConversationParticipant Join(
        Guid conversationId,
        string userId,
        ConversationParticipantRole role,
        DateTimeOffset joinedAtUtc)
    {
        return new ConversationParticipant(
            conversationId,
            userId,
            role,
            joinedAtUtc);
    }

    internal void Rejoin(
        ConversationParticipantRole role,
        DateTimeOffset joinedAtUtc)
    {
        if (IsActive)
        {
            throw new InvalidOperationException(
                "The participant is already active.");
        }

        Role = role;
        JoinedAtUtc = joinedAtUtc;
        LeftAtUtc = null;
    }

    internal void Leave(DateTimeOffset leftAtUtc)
    {
        if (!IsActive)
        {
            return;
        }

        if (leftAtUtc < JoinedAtUtc)
        {
            throw new ArgumentOutOfRangeException(
                nameof(leftAtUtc),
                "Leave time cannot predate join time.");
        }

        LeftAtUtc = leftAtUtc;
    }
}
