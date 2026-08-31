using System.Security.Claims;
using Microsoft.Extensions.Options;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.BuildingBlocks.Authentication;

namespace SentriCam.Infrastructure.Authentication;

public sealed class JwtOperatorAccessTokenIssuer(
    IOptions<JwtOptions> options,
    TimeProvider timeProvider)
    : IOperatorAccessTokenIssuer
{
    private readonly JwtOptions _options = options.Value;
    private readonly JwtTokenFactory _factory = new(options.Value);
    private readonly TimeProvider _timeProvider = timeProvider;

    public OperatorAccessToken Issue(DateTimeOffset issuedAtUtc)
    {
        var now = issuedAtUtc == default ? _timeProvider.GetUtcNow() : issuedAtUtc;
        var expiresAtUtc = now.AddMinutes(_options.DevelopmentOperatorTokenLifetimeMinutes);
        var claims = new[]
        {
            new Claim("role", AuthorizationRoles.DeviceOperator),
            new Claim("role", AuthorizationRoles.HubAdministrator),
            new Claim(AuthenticationClaimNames.TokenType, AuthenticationTokenTypes.Operator),
        };

        return new OperatorAccessToken(
            _factory.Issue(_options.DevelopmentOperatorSubject, now, expiresAtUtc, claims),
            expiresAtUtc);
    }
}
