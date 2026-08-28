namespace BotGlobal.Identity.Application;

public sealed record MobileGuestRequest(string DisplayName);

public sealed record MobileLoginRequest(string UserNameOrEmail, string Password);

public sealed record MobileRegistrationRequest(
    string UserName,
    string Email,
    string DisplayName,
    string Password);

public sealed record MobileRefreshRequest(string RefreshToken);

public sealed record MobileSessionResponse(
    string AccessToken,
    DateTimeOffset AccessExpiresAtUtc,
    string RefreshToken,
    DateTimeOffset RefreshExpiresAtUtc,
    MobileIdentityResponse Identity);

public sealed record MobileIdentityResponse(
    Guid MembershipId,
    string SubjectId,
    string DisplayName,
    bool IsGuest,
    string ApplicationKey);

public sealed record MobileIdentityResult(
    MobileSessionResponse? Session,
    IReadOnlyDictionary<string, string[]> Errors)
{
    public bool Succeeded => Session is not null;

    public static MobileIdentityResult Success(MobileSessionResponse session) =>
        new(session, new Dictionary<string, string[]>());

    public static MobileIdentityResult Failure(string key, params string[] errors) =>
        new(null, new Dictionary<string, string[]> { [key] = errors });
}
