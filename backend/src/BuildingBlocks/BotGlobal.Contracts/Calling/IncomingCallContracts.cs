namespace BotGlobal.Contracts.Calling;

public sealed record CallingParticipantDescriptor(
    Guid MembershipId,
    string ApplicationKey,
    string SubjectId,
    string DisplayName,
    bool IsActive);

public enum CallingParticipantAvailability
{
    Online = 1,
    Reachable = 2,
    Offline = 3
}

public interface ICallingReachabilityResolver
{
    Task<IReadOnlySet<Guid>> FindReachableMembershipsAsync(
        string applicationKey,
        IReadOnlyCollection<CallingParticipantDescriptor> participants,
        CancellationToken cancellationToken);
}

public interface ICallingParticipantDirectory
{
    Task<IReadOnlyList<CallingParticipantDescriptor>> ListCallableAsync(
        string applicationKey,
        Guid currentMembershipId,
        CancellationToken cancellationToken);

    Task<CallingParticipantDescriptor?> FindAsync(
        string applicationKey,
        Guid membershipId,
        CancellationToken cancellationToken);
}

public enum IncomingCallNotificationKind
{
    Offered = 1,
    Cancelled = 2,
    AnsweredElsewhere = 3,
    Expired = 4
}

public sealed record IncomingCallNotification(
    string ApplicationKey,
    string RecipientSubjectId,
    Guid CallId,
    IncomingCallNotificationKind Kind,
    string CallerDisplayName,
    DateTimeOffset ExpiresAtUtc);

public interface IIncomingCallNotificationDispatcher
{
    Task DispatchAsync(IncomingCallNotification notification, CancellationToken cancellationToken);
}
