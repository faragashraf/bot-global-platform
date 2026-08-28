namespace BotGlobal.Games.Application.Entitlements;

public interface IGameEntitlementAuthorizer
{
    Task<bool> IsAllowedAsync(
        Guid membershipId,
        string? requiredEntitlement,
        CancellationToken cancellationToken);
}

internal sealed class FreeGameEntitlementAuthorizer : IGameEntitlementAuthorizer
{
    public Task<bool> IsAllowedAsync(
        Guid membershipId,
        string? requiredEntitlement,
        CancellationToken cancellationToken) =>
        Task.FromResult(string.IsNullOrWhiteSpace(requiredEntitlement));
}
