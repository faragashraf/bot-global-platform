using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

internal sealed class MobileFederatedIdentityService(
    IdentityDbContext dbContext,
    UserManager<ApplicationUser> userManager,
    IFederatedIdentityTokenValidator validator,
    IMobileApplicationTokenService tokenService,
    TimeProvider timeProvider) : IMobileFederatedIdentityService
{
    public async Task<MobileIdentityResult> AuthenticateAsync(
        string applicationKey,
        MobileFederatedIdentityRequest request,
        CancellationToken cancellationToken)
    {
        var validated = await validator.ValidateAsync(request.Provider, request.IdToken, cancellationToken);
        if (validated.Identity is not { } external)
        {
            return MobileIdentityResult.Failure("federatedIdentity", validated.Error ?? "federated_identity_rejected");
        }

        var user = await userManager.FindByLoginAsync(external.Provider, external.ProviderSubject);
        if (user is null)
        {
            var sameEmail = await userManager.FindByEmailAsync(external.Email);
            if (sameEmail is not null)
            {
                return MobileIdentityResult.Failure("accountLink", "account_link_required");
            }

            user = new ApplicationUser(
                Guid.NewGuid(),
                $"{external.Provider}_{external.ProviderSubject}",
                external.Email,
                external.DisplayName);
            var created = await userManager.CreateAsync(user);
            if (!created.Succeeded)
            {
                return IdentityFailure(created);
            }

            var linked = await userManager.AddLoginAsync(
                user,
                new UserLoginInfo(external.Provider, external.ProviderSubject, external.Provider));
            if (!linked.Succeeded)
            {
                await userManager.DeleteAsync(user);
                return IdentityFailure(linked);
            }
        }

        if (!user.IsActive)
        {
            return MobileIdentityResult.Failure("identity", "identity_inactive");
        }

        var membership = await dbContext.ApplicationMemberships.SingleOrDefaultAsync(
            x => x.ApplicationKey == applicationKey && x.GlobalUserId == user.Id,
            cancellationToken);
        if (membership is null)
        {
            membership = new ApplicationMembership(
                Guid.NewGuid(),
                applicationKey,
                $"user:{user.Id:N}",
                user.DisplayName,
                user.Id,
                false,
                timeProvider.GetUtcNow());
            dbContext.ApplicationMemberships.Add(membership);
        }

        var issued = await tokenService.IssueAsync(membership, cancellationToken);
        return MobileIdentityResult.Success(
            new MobileSessionResponse(
                issued.AccessToken,
                issued.Session.AccessExpiresAtUtc,
                issued.RefreshToken,
                issued.Session.RefreshExpiresAtUtc,
                new MobileIdentityResponse(
                    membership.Id,
                    membership.SubjectId,
                    membership.DisplayName,
                    membership.IsGuest,
                    membership.ApplicationKey)));
    }

    private static MobileIdentityResult IdentityFailure(IdentityResult result) =>
        new(null, result.Errors
            .GroupBy(x => x.Code, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(x => x.Key, x => x.Select(error => error.Description).ToArray()));
}
