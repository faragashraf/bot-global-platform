using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Infrastructure;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

public sealed record PublicNqrbAccountDeletionRequest(string? IdToken);

public enum PublicNqrbAccountDeletionOutcome
{
    Accepted,
    InvalidGoogleCredential,
    ConfigurationUnavailable
}

public interface IPublicNqrbAccountDeletionService
{
    Task<PublicNqrbAccountDeletionOutcome> VerifyAsync(
        PublicNqrbAccountDeletionRequest request,
        CancellationToken cancellationToken);

    Task<PublicNqrbAccountDeletionOutcome> DeleteAsync(
        PublicNqrbAccountDeletionRequest request,
        CancellationToken cancellationToken);
}

internal sealed class PublicNqrbAccountDeletionService(
    IdentityDbContext dbContext,
    INqrbWebGoogleIdentityValidator google,
    IApplicationAccountDeletionService deletion) : IPublicNqrbAccountDeletionService
{
    public async Task<PublicNqrbAccountDeletionOutcome> VerifyAsync(
        PublicNqrbAccountDeletionRequest request,
        CancellationToken cancellationToken)
    {
        var validated = await google.ValidateAsync(request.IdToken ?? string.Empty, cancellationToken);
        return ValidationOutcome(validated);
    }

    public async Task<PublicNqrbAccountDeletionOutcome> DeleteAsync(
        PublicNqrbAccountDeletionRequest request,
        CancellationToken cancellationToken)
    {
        var validated = await google.ValidateAsync(request.IdToken ?? string.Empty, cancellationToken);
        var outcome = ValidationOutcome(validated);
        if (outcome != PublicNqrbAccountDeletionOutcome.Accepted)
        {
            return outcome;
        }

        var external = validated.Identity!;
        var login = await dbContext.Set<IdentityUserLogin<Guid>>()
            .AsNoTracking()
            .SingleOrDefaultAsync(
                item => item.LoginProvider == FederatedIdentityProviders.Google
                    && item.ProviderKey == external.ProviderSubject,
                cancellationToken);
        if (login is null)
        {
            return PublicNqrbAccountDeletionOutcome.Accepted;
        }

        var membership = await dbContext.ApplicationMemberships
            .AsNoTracking()
            .SingleOrDefaultAsync(
                item => item.GlobalUserId == login.UserId
                    && item.ApplicationKey == BotGlobalApplications.Nqrb,
                cancellationToken);
        if (membership is null)
        {
            return PublicNqrbAccountDeletionOutcome.Accepted;
        }

        await deletion.DeleteAsync(
            new ApplicationIdentityDescriptor(
                membership.Id,
                login.UserId,
                membership.SubjectId,
                BotGlobalApplications.Nqrb,
                membership.DisplayName,
                membership.IsGuest),
            cancellationToken);
        return PublicNqrbAccountDeletionOutcome.Accepted;
    }

    private static PublicNqrbAccountDeletionOutcome ValidationOutcome(
        FederatedIdentityValidationResult validation) => validation switch
        {
            { Succeeded: true } => PublicNqrbAccountDeletionOutcome.Accepted,
            { Error: "google_web_configuration_missing" } =>
                PublicNqrbAccountDeletionOutcome.ConfigurationUnavailable,
            _ => PublicNqrbAccountDeletionOutcome.InvalidGoogleCredential
        };
}
