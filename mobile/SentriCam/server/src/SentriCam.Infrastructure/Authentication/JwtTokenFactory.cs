using System.Globalization;
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace SentriCam.Infrastructure.Authentication;

internal sealed class JwtTokenFactory
{
    private readonly JwtOptions _options;
    private readonly SigningCredentials _signingCredentials;

    public JwtTokenFactory(IOptions<JwtOptions> options)
        : this(options.Value)
    {
    }

    public JwtTokenFactory(JwtOptions options)
    {
        _options = options;
        _signingCredentials = new SigningCredentials(
            new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_options.SigningKey)),
            SecurityAlgorithms.HmacSha256);
    }

    public string Issue(
        string subject,
        DateTimeOffset issuedAtUtc,
        DateTimeOffset expiresAtUtc,
        IEnumerable<Claim> additionalClaims)
    {
        var claims = new List<Claim>(additionalClaims)
        {
            new Claim(
                JwtRegisteredClaimNames.Sub,
                subject),
            new Claim(
                JwtRegisteredClaimNames.Jti,
                Guid.NewGuid().ToString("N")),
            new Claim(
                JwtRegisteredClaimNames.Iat,
                EpochTime.GetIntDate(issuedAtUtc.UtcDateTime).ToString(CultureInfo.InvariantCulture),
                ClaimValueTypes.Integer64),
        };

        var token = new JwtSecurityToken(
            _options.Issuer,
            _options.Audience,
            claims,
            issuedAtUtc.UtcDateTime,
            expiresAtUtc.UtcDateTime,
            _signingCredentials);

        return new JwtSecurityTokenHandler().WriteToken(token);
    }
}
