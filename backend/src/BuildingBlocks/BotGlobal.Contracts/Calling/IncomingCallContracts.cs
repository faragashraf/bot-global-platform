using System.Text.Json.Serialization;

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

public sealed record CallingAccountDescriptor(
    Guid MembershipId,
    string DisplayName,
    [property: JsonIgnore] string SubjectId = "");

public sealed record CallingAccountSearchPage(
    IReadOnlyList<CallingAccountDescriptor> Items,
    int Page,
    int PageSize,
    bool HasMore);

public interface ICallingAccountDirectory
{
    Task<CallingAccountDescriptor?> FindActiveNonGuestAsync(
        string applicationKey,
        Guid membershipId,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<CallingAccountDescriptor>> FindActiveNonGuestAsync(
        string applicationKey,
        IReadOnlyCollection<Guid> membershipIds,
        CancellationToken cancellationToken);

    Task<CallingAccountSearchPage> SearchActiveNonGuestsAsync(
        string applicationKey,
        Guid currentMembershipId,
        string query,
        int page,
        int pageSize,
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
