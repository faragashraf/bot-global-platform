using BotGlobal.Identity.Application;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.Identity.Endpoints;

internal static class PublicNqrbAccountDeletionEndpoints
{
    public static IEndpointRouteBuilder MapPublicNqrbAccountDeletionEndpoints(
        this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapPost(
                "/api/public/nqrb/account-deletion/verify",
                async (
                    PublicNqrbAccountDeletionRequest request,
                    IPublicNqrbAccountDeletionService service,
                    CancellationToken cancellationToken) =>
                    ToVerificationResult(await service.VerifyAsync(request, cancellationToken)))
            .AllowAnonymous()
            .RequireRateLimiting(IdentityModule.PublicNqrbAccountDeletionRateLimitPolicy);

        endpoints.MapPost(
                "/api/public/nqrb/account-deletion",
                async (
                    PublicNqrbAccountDeletionRequest request,
                    IPublicNqrbAccountDeletionService service,
                    CancellationToken cancellationToken) =>
                    ToDeletionResult(await service.DeleteAsync(request, cancellationToken)))
            .AllowAnonymous()
            .RequireRateLimiting(IdentityModule.PublicNqrbAccountDeletionRateLimitPolicy);

        return endpoints;
    }

    private static IResult ToVerificationResult(PublicNqrbAccountDeletionOutcome outcome) => outcome switch
    {
        PublicNqrbAccountDeletionOutcome.Accepted => Results.NoContent(),
        PublicNqrbAccountDeletionOutcome.InvalidGoogleCredential => Results.Unauthorized(),
        _ => Results.StatusCode(StatusCodes.Status503ServiceUnavailable)
    };

    private static IResult ToDeletionResult(PublicNqrbAccountDeletionOutcome outcome) => outcome switch
    {
        PublicNqrbAccountDeletionOutcome.Accepted => Results.Accepted(),
        PublicNqrbAccountDeletionOutcome.InvalidGoogleCredential => Results.Unauthorized(),
        _ => Results.StatusCode(StatusCodes.Status503ServiceUnavailable)
    };
}
