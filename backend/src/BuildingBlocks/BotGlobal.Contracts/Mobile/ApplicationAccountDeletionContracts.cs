namespace BotGlobal.Contracts.Mobile;

public sealed record ApplicationAccountDeletionIdentity(
    Guid MembershipId,
    Guid GlobalUserId,
    string SubjectId,
    string ApplicationKey);

public sealed class ApplicationAccountDeletionScope
{
    private readonly HashSet<Guid> mobileDeviceIds;

    public ApplicationAccountDeletionScope(
        ApplicationAccountDeletionIdentity identity,
        IReadOnlyCollection<Guid> mobileDeviceIds)
    {
        Identity = identity;
        this.mobileDeviceIds = mobileDeviceIds
            .Where(value => value != Guid.Empty)
            .ToHashSet();
    }

    public ApplicationAccountDeletionIdentity Identity { get; }

    public IReadOnlyCollection<Guid> MobileDeviceIds => mobileDeviceIds;

    public void IncludeMobileDeviceIds(IEnumerable<Guid> deviceIds)
    {
        foreach (var deviceId in deviceIds.Where(value => value != Guid.Empty))
        {
            mobileDeviceIds.Add(deviceId);
        }
    }
}

public interface IApplicationAccountDeletionHandler
{
    string StepName { get; }

    int Order { get; }

    bool RevokesAccess => false;

    Task DeleteAsync(
        ApplicationAccountDeletionScope scope,
        CancellationToken cancellationToken);
}

public interface IApplicationMembershipActivityReader
{
    Task<bool> IsActiveAsync(
        Guid membershipId,
        string applicationKey,
        CancellationToken cancellationToken);

    Task<bool> IsActiveAsync(
        Guid membershipId,
        string applicationKey,
        string subjectId,
        CancellationToken cancellationToken) =>
        IsActiveAsync(membershipId, applicationKey, cancellationToken);
}

public sealed class UnavailableApplicationMembershipActivityReader
    : IApplicationMembershipActivityReader
{
    public Task<bool> IsActiveAsync(
        Guid membershipId,
        string applicationKey,
        CancellationToken cancellationToken) => Task.FromResult(false);
}
