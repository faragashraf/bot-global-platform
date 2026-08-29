using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

public interface IMobileIdentityService
{
    Task<MobileIdentityResult> ContinueAsGuestAsync(string applicationKey, MobileGuestRequest request, CancellationToken cancellationToken);
    Task<MobileIdentityResult> RegisterAsync(string applicationKey, MobileRegistrationRequest request, CancellationToken cancellationToken);
    Task<MobileIdentityResult> LoginAsync(string applicationKey, MobileLoginRequest request, CancellationToken cancellationToken);
    Task<MobileIdentityResult> UpgradeGuestAsync(Guid membershipId, MobileRegistrationRequest request, CancellationToken cancellationToken);
    Task<MobileSessionResponse?> RefreshAsync(string applicationKey, MobileRefreshRequest request, CancellationToken cancellationToken);
}

internal sealed class MobileIdentityService(
    IdentityDbContext dbContext,
    UserManager<ApplicationUser> userManager,
    SignInManager<ApplicationUser> signInManager,
    IMobileApplicationTokenService tokenService,
    TimeProvider timeProvider) : IMobileIdentityService
{
    public async Task<MobileIdentityResult> ContinueAsGuestAsync(
        string applicationKey,
        MobileGuestRequest request,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(request.DisplayName))
        {
            return MobileIdentityResult.Failure("displayName", "Display name is required.");
        }

        var membership = new ApplicationMembership(
            Guid.NewGuid(),
            applicationKey,
            $"guest:{Guid.NewGuid():N}",
            request.DisplayName,
            null,
            true,
            timeProvider.GetUtcNow());
        dbContext.ApplicationMemberships.Add(membership);
        return await IssueAsync(membership, cancellationToken);
    }

    public async Task<MobileIdentityResult> RegisterAsync(
        string applicationKey,
        MobileRegistrationRequest request,
        CancellationToken cancellationToken)
    {
        var validation = ValidateRegistration(request);
        if (validation is not null)
        {
            return validation;
        }

        var user = new ApplicationUser(
            Guid.NewGuid(),
            request.UserName,
            request.Email,
            request.DisplayName);
        var created = await userManager.CreateAsync(user, request.Password);
        if (!created.Succeeded)
        {
            return IdentityFailure(created);
        }

        var membership = await GetOrCreateMembershipAsync(applicationKey, user, cancellationToken);
        return await IssueAsync(membership, cancellationToken);
    }

    public async Task<MobileIdentityResult> LoginAsync(
        string applicationKey,
        MobileLoginRequest request,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(request.UserNameOrEmail) || string.IsNullOrWhiteSpace(request.Password))
        {
            return MobileIdentityResult.Failure("credentials", "Username/email and password are required.");
        }

        var lookup = request.UserNameOrEmail.Trim();
        var user = lookup.Contains('@')
            ? await userManager.FindByEmailAsync(lookup)
            : await userManager.FindByNameAsync(lookup);

        if (user is null || !user.IsActive)
        {
            return MobileIdentityResult.Failure("credentials", "The supplied credentials are invalid.");
        }

        var passwordResult = await signInManager.CheckPasswordSignInAsync(
            user,
            request.Password,
            lockoutOnFailure: true);
        if (!passwordResult.Succeeded)
        {
            return MobileIdentityResult.Failure("credentials", "The supplied credentials are invalid.");
        }

        var membership = await GetOrCreateMembershipAsync(applicationKey, user, cancellationToken);
        return await IssueAsync(membership, cancellationToken);
    }

    public async Task<MobileIdentityResult> UpgradeGuestAsync(
        Guid membershipId,
        MobileRegistrationRequest request,
        CancellationToken cancellationToken)
    {
        var validation = ValidateRegistration(request);
        if (validation is not null)
        {
            return validation;
        }

        var membership = await dbContext.ApplicationMemberships
            .SingleOrDefaultAsync(x => x.Id == membershipId, cancellationToken);
        if (membership is null || !membership.IsActive || !membership.IsGuest)
        {
            return MobileIdentityResult.Failure("membership", "An active guest membership is required.");
        }

        var user = new ApplicationUser(Guid.NewGuid(), request.UserName, request.Email, request.DisplayName);
        var created = await userManager.CreateAsync(user, request.Password);
        if (!created.Succeeded)
        {
            return IdentityFailure(created);
        }

        membership.Upgrade(user.Id, $"user:{user.Id:N}", user.DisplayName, timeProvider.GetUtcNow());
        await dbContext.SaveChangesAsync(cancellationToken);
        return await IssueAsync(membership, cancellationToken);
    }

    public async Task<MobileSessionResponse?> RefreshAsync(
        string applicationKey,
        MobileRefreshRequest request,
        CancellationToken cancellationToken)
    {
        var issued = await tokenService.RefreshAsync(request.RefreshToken, applicationKey, cancellationToken);
        return issued is null ? null : ToResponse(issued, issued.Session.Membership);
    }

    private async Task<ApplicationMembership> GetOrCreateMembershipAsync(
        string applicationKey,
        ApplicationUser user,
        CancellationToken cancellationToken)
    {
        var existing = await dbContext.ApplicationMemberships
            .SingleOrDefaultAsync(
                x => x.ApplicationKey == applicationKey && x.GlobalUserId == user.Id,
                cancellationToken);
        if (existing is not null)
        {
            return existing;
        }

        var membership = new ApplicationMembership(
            Guid.NewGuid(),
            applicationKey,
            $"user:{user.Id:N}",
            user.DisplayName,
            user.Id,
            false,
            timeProvider.GetUtcNow());
        dbContext.ApplicationMemberships.Add(membership);
        return membership;
    }

    private async Task<MobileIdentityResult> IssueAsync(
        ApplicationMembership membership,
        CancellationToken cancellationToken)
    {
        var issued = await tokenService.IssueAsync(membership, cancellationToken);
        return MobileIdentityResult.Success(ToResponse(issued, membership));
    }

    private static MobileSessionResponse ToResponse(
        IssuedMobileApplicationSession issued,
        ApplicationMembership membership) =>
        new(
            issued.AccessToken,
            issued.Session.AccessExpiresAtUtc,
            issued.RefreshToken,
            issued.Session.RefreshExpiresAtUtc,
            new MobileIdentityResponse(
                membership.Id,
                membership.SubjectId,
                membership.DisplayName,
                membership.IsGuest,
                membership.ApplicationKey));

    private static MobileIdentityResult? ValidateRegistration(MobileRegistrationRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.UserName) ||
            string.IsNullOrWhiteSpace(request.Email) ||
            string.IsNullOrWhiteSpace(request.DisplayName) ||
            string.IsNullOrWhiteSpace(request.Password))
        {
            return MobileIdentityResult.Failure("registration", "All registration fields are required.");
        }

        return null;
    }

    private static MobileIdentityResult IdentityFailure(IdentityResult result) =>
        new(null, result.Errors
            .GroupBy(x => x.Code, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(x => x.Key, x => x.Select(error => error.Description).ToArray()));
}
