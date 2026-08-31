namespace SentriCam.Infrastructure.Authentication;

public sealed class JwtOptions
{
    public const string SectionName = "Authentication:Jwt";

    public string Issuer { get; init; } = string.Empty;

    public string Audience { get; init; } = string.Empty;

    public string SigningKey { get; init; } = string.Empty;

    public int AccessTokenLifetimeMinutes { get; init; } = 60;

    public int DevelopmentOperatorTokenLifetimeMinutes { get; init; } = 15;

    public string DevelopmentOperatorSubject { get; init; } = "sentricam-dev-operator";

    public string DevelopmentOperatorDisplayName { get; init; } = "Local Development Operator";
}
