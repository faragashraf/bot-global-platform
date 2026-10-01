using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using Microsoft.Extensions.Options;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Domain.Devices;
using SentriCam.BuildingBlocks.Authentication;

namespace SentriCam.Infrastructure.Authentication;

public sealed class JwtDeviceAccessTokenIssuer(IOptions<JwtOptions> options)
    : IDeviceAccessTokenIssuer
{
    private readonly JwtOptions _options = options.Value;
    private readonly JwtTokenFactory _factory = new(options.Value);

    public DeviceAccessToken Issue(
        DeviceId deviceId,
        string installationId,
        DateTimeOffset issuedAtUtc) =>
        Issue(deviceId, installationId, Guid.NewGuid(), issuedAtUtc);

    public DeviceAccessToken Issue(
        DeviceId deviceId,
        string installationId,
        Guid registrationId,
        DateTimeOffset issuedAtUtc)
    {
        var expiresAtUtc = issuedAtUtc.AddMinutes(_options.AccessTokenLifetimeMinutes);
        var claims = new[]
        {
            new Claim("client_id", installationId),
            new Claim(AuthenticationClaimNames.TokenType, AuthenticationTokenTypes.Device),
            new Claim(AuthenticationClaimNames.DeviceId, deviceId.ToString()),
            new Claim(AuthenticationClaimNames.InstallationId, installationId),
            new Claim(AuthenticationClaimNames.RegistrationId, registrationId.ToString("D")),
        };

        return new DeviceAccessToken(
            _factory.Issue(deviceId.ToString(), issuedAtUtc, expiresAtUtc, claims),
            expiresAtUtc);
    }
}
