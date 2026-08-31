using System.IdentityModel.Tokens.Jwt;
using Microsoft.Extensions.Options;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Infrastructure.Authentication;
using SentriCam.Tests.TestDoubles;

namespace SentriCam.Tests.Infrastructure;

public sealed class JwtOperatorAccessTokenIssuerTests
{
    [Fact]
    public void IssuedTokenCarriesOperatorClaimsAndExpiry()
    {
        var options = Options.Create(new JwtOptions
        {
            Issuer = "SentriCam.Tests",
            Audience = "SentriCam.TestClients",
            SigningKey = "test-only-signing-key-with-at-least-thirty-two-chars",
            DevelopmentOperatorTokenLifetimeMinutes = 7,
            DevelopmentOperatorSubject = "sentricam-dev-operator",
            DevelopmentOperatorDisplayName = "Local Development Operator",
        });
        var issuer = new JwtOperatorAccessTokenIssuer(options, TimeProvider.System);
        var issuedAt = DeviceTestFactory.Now;

        var result = issuer.Issue(issuedAt);
        var token = new JwtSecurityTokenHandler().ReadJwtToken(result.Value);

        Assert.Equal(issuedAt.AddMinutes(7), result.ExpiresAtUtc);
        Assert.Equal("sentricam-dev-operator", token.Subject);
        Assert.Equal("SentriCam.Tests", token.Issuer);
        Assert.Contains(token.Audiences, audience => audience == "SentriCam.TestClients");
        Assert.Contains(token.Claims, claim =>
            claim.Type == "role" && claim.Value == AuthorizationRoles.DeviceOperator);
        Assert.Contains(token.Claims, claim =>
            claim.Type == AuthenticationClaimNames.TokenType
                && claim.Value == AuthenticationTokenTypes.Operator);
    }
}
