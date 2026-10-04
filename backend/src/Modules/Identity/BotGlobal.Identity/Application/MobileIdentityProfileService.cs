using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

public sealed record MobileIdentityProfileUpdateRequest(string DisplayName);

public interface IMobileIdentityProfileService
{
    Task<MobileIdentityProfileUpdateResult> UpdateAsync(
        ApplicationIdentityDescriptor identity,
        MobileIdentityProfileUpdateRequest request,
        CancellationToken cancellationToken);
}

public sealed record MobileIdentityProfileUpdateResult(
    MobileIdentityResponse? Identity,
    IReadOnlyDictionary<string, string[]> Errors)
{
    public bool Succeeded => Identity is not null;

    public static MobileIdentityProfileUpdateResult Success(MobileIdentityResponse identity) =>
        new(identity, new Dictionary<string, string[]>());

    public static MobileIdentityProfileUpdateResult Failure(string key, params string[] errors) =>
        new(null, new Dictionary<string, string[]> { [key] = errors });
}

internal sealed class MobileIdentityProfileService(IdentityDbContext dbContext)
    : IMobileIdentityProfileService
{
    public async Task<MobileIdentityProfileUpdateResult> UpdateAsync(
        ApplicationIdentityDescriptor identity,
        MobileIdentityProfileUpdateRequest request,
        CancellationToken cancellationToken)
    {
        if (identity.IsGuest)
        {
            return MobileIdentityProfileUpdateResult.Failure("identity", "guest_profile_update_not_supported");
        }

        var displayName = request.DisplayName?.Trim();
        if (string.IsNullOrWhiteSpace(displayName))
        {
            return MobileIdentityProfileUpdateResult.Failure("displayName", "display_name_required");
        }

        if (displayName.Length > 120)
        {
            return MobileIdentityProfileUpdateResult.Failure("displayName", "display_name_too_long");
        }

        var membership = await dbContext.ApplicationMemberships.SingleOrDefaultAsync(
            item => item.Id == identity.MembershipId
                && item.ApplicationKey == identity.ApplicationKey
                && item.GlobalUserId == identity.GlobalUserId,
            cancellationToken);
        if (membership is null || !membership.IsActive)
        {
            return MobileIdentityProfileUpdateResult.Failure("identity", "identity_inactive");
        }

        membership.UpdateDisplayName(displayName);
        await dbContext.SaveChangesAsync(cancellationToken);
        return MobileIdentityProfileUpdateResult.Success(new MobileIdentityResponse(
            membership.Id,
            membership.SubjectId,
            membership.DisplayName,
            membership.IsGuest,
            membership.ApplicationKey));
    }
}
