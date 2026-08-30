using BotGlobal.Identity.Application;
using Google.Apis.Auth;
using Microsoft.Extensions.Options;

namespace BotGlobal.Identity.Infrastructure;

public sealed class GoogleFederatedIdentityOptions
{
    public const string SectionName = "Identity:Federated:Google";
    public string ServerClientId { get; set; } = string.Empty;
}

internal interface IGoogleIdTokenVerifier
{
    Task<GoogleTokenVerificationResult> VerifyAsync(
        string idToken,
        string expectedAudience,
        CancellationToken cancellationToken);
}

internal sealed record GoogleTokenClaims(
    string Subject,
    string Email,
    bool EmailVerified,
    string DisplayName);

internal sealed record GoogleTokenVerificationResult(
    GoogleTokenClaims? Claims,
    string? Error)
{
    public static GoogleTokenVerificationResult Success(GoogleTokenClaims claims) => new(claims, null);
    public static GoogleTokenVerificationResult Failure(string error) => new(null, error);
}

internal sealed class GoogleIdTokenVerifier : IGoogleIdTokenVerifier
{
    public async Task<GoogleTokenVerificationResult> VerifyAsync(
        string idToken,
        string expectedAudience,
        CancellationToken cancellationToken)
    {
        try
        {
            var payload = await GoogleJsonWebSignature.ValidateAsync(
                idToken,
                new GoogleJsonWebSignature.ValidationSettings
                {
                    Audience = [expectedAudience]
                });
            cancellationToken.ThrowIfCancellationRequested();
            return GoogleTokenVerificationResult.Success(
                new GoogleTokenClaims(
                    payload.Subject ?? string.Empty,
                    payload.Email ?? string.Empty,
                    payload.EmailVerified,
                    payload.Name ?? string.Empty));
        }
        catch (InvalidJwtException)
        {
            return GoogleTokenVerificationResult.Failure("invalid_google_token");
        }
    }
}

internal sealed class GoogleFederatedIdentityTokenValidator(
    IOptions<GoogleFederatedIdentityOptions> options,
    IGoogleIdTokenVerifier google) : IFederatedIdentityTokenValidator
{
    public async Task<FederatedIdentityValidationResult> ValidateAsync(
        string provider,
        string idToken,
        CancellationToken cancellationToken)
    {
        if (!string.Equals(provider, FederatedIdentityProviders.Google, StringComparison.OrdinalIgnoreCase))
        {
            return FederatedIdentityValidationResult.Failure("provider_not_supported");
        }

        var audience = options.Value.ServerClientId.Trim();
        if (audience.Length == 0)
        {
            return FederatedIdentityValidationResult.Failure("google_configuration_missing");
        }

        if (string.IsNullOrWhiteSpace(idToken))
        {
            return FederatedIdentityValidationResult.Failure("invalid_google_token");
        }

        var verified = await google.VerifyAsync(idToken, audience, cancellationToken);
        if (verified.Claims is not { } claims)
        {
            return FederatedIdentityValidationResult.Failure(verified.Error ?? "invalid_google_token");
        }

        if (string.IsNullOrWhiteSpace(claims.Subject) ||
            string.IsNullOrWhiteSpace(claims.Email) ||
            !claims.EmailVerified)
        {
            return FederatedIdentityValidationResult.Failure("google_identity_incomplete");
        }

        var displayName = string.IsNullOrWhiteSpace(claims.DisplayName)
            ? claims.Email.Split('@')[0]
            : claims.DisplayName.Trim();
        return FederatedIdentityValidationResult.Success(
            new ValidatedFederatedIdentity(
                FederatedIdentityProviders.Google,
                claims.Subject.Trim(),
                claims.Email.Trim(),
                displayName));
    }
}
