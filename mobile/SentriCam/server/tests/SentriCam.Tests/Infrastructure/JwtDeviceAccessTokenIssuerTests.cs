using System.IdentityModel.Tokens.Jwt;
using Microsoft.Extensions.Options;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Infrastructure.Authentication;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Infrastructure;

public sealed class JwtDeviceAccessTokenIssuerTests
{
    [Fact]
    public void IssuedTokenCarriesStableDeviceClaimsAndExpiry()
    {
        var options = Options.Create(new JwtOptions
        {
            Issuer = "SentriCam.Tests",
            Audience = "SentriCam.TestClients",
            SigningKey = "test-only-signing-key-with-at-least-thirty-two-characters",
            AccessTokenLifetimeMinutes = 90,
        });
        var issuer = new JwtDeviceAccessTokenIssuer(options);
        var device = DeviceTestFactory.CreateDevice();

        var result = issuer.Issue(device.Id, device.Identity.InstallationId, DeviceTestFactory.Now);
        var token = new JwtSecurityTokenHandler().ReadJwtToken(result.Value);

        Assert.Equal(DeviceTestFactory.Now.AddMinutes(90), result.ExpiresAtUtc);
        Assert.Equal(device.Id.ToString(), token.Subject);
        Assert.Contains(token.Claims, claim =>
            claim.Type == AuthenticationClaimNames.TokenType
            && claim.Value == AuthenticationTokenTypes.Device);
        Assert.Contains(token.Claims, claim =>
            claim.Type == AuthenticationClaimNames.InstallationId
            && claim.Value == device.Identity.InstallationId);
        Assert.Contains(token.Claims, claim =>
            claim.Type == JwtRegisteredClaimNames.Iat
            && long.TryParse(claim.Value, out _));
        Assert.Contains(token.Claims, claim =>
            claim.Type == "client_id"
            && claim.Value == device.Identity.InstallationId);
    }
}
