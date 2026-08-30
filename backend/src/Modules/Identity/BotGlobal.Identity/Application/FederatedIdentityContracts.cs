namespace BotGlobal.Identity.Application;

public static class FederatedIdentityProviders
{
    public const string Google = "google";
    public const string Apple = "apple";
}

public sealed record MobileFederatedIdentityRequest(
    string Provider,
    string IdToken);

public sealed record ValidatedFederatedIdentity(
    string Provider,
    string ProviderSubject,
    string Email,
    string DisplayName);

public sealed record FederatedIdentityValidationResult(
    ValidatedFederatedIdentity? Identity,
    string? Error)
{
    public bool Succeeded => Identity is not null;

    public static FederatedIdentityValidationResult Success(ValidatedFederatedIdentity identity) => new(identity, null);
    public static FederatedIdentityValidationResult Failure(string error) => new(null, error);
}

public interface IFederatedIdentityTokenValidator
{
    Task<FederatedIdentityValidationResult> ValidateAsync(
        string provider,
        string idToken,
        CancellationToken cancellationToken);
}

public interface IMobileFederatedIdentityService
{
    Task<MobileIdentityResult> AuthenticateAsync(
        string applicationKey,
        MobileFederatedIdentityRequest request,
        CancellationToken cancellationToken);
}
