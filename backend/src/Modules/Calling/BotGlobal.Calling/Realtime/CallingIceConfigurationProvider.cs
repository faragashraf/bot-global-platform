using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;

namespace BotGlobal.Calling.Realtime;

public sealed class CallingIceOptions
{
    public const string SectionName = "Calling:Ice";
    public string[] StunUrls { get; set; } = [];
    public string[] TurnUrls { get; set; } = [];
    public string? TurnRestSecret { get; set; }
    public int CredentialLifetimeMinutes { get; set; } = 60;
}

public sealed class CallingIceConfigurationProvider(IOptions<CallingIceOptions> options, TimeProvider timeProvider)
{
    public CallingIceConfiguration Create(Guid membershipId)
    {
        var configured = options.Value;
        var expires = timeProvider.GetUtcNow().AddMinutes(configured.CredentialLifetimeMinutes);
        var servers = new List<CallingIceServer>();
        if (configured.StunUrls.Length > 0) servers.Add(new CallingIceServer(configured.StunUrls, null, null));
        if (configured.TurnUrls.Length > 0 && !string.IsNullOrWhiteSpace(configured.TurnRestSecret))
        {
            var username = $"{expires.ToUnixTimeSeconds()}:{membershipId:N}";
            using var hmac = new HMACSHA1(Encoding.UTF8.GetBytes(configured.TurnRestSecret));
            var credential = Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(username)));
            servers.Add(new CallingIceServer(configured.TurnUrls, username, credential));
        }
        return new CallingIceConfiguration(servers, expires);
    }
}
