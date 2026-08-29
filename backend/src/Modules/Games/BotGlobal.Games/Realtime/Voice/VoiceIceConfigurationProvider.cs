using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;

namespace BotGlobal.Games.Realtime.Voice;

public sealed class VoiceIceOptions
{
    public const string SectionName = "Games:Voice:Ice";
    public string[] StunUrls { get; set; } = [];
    public string[] TurnUrls { get; set; } = [];
    public string? TurnRestSecret { get; set; }
    public int CredentialLifetimeMinutes { get; set; } = 60;
}

public interface IVoiceIceConfigurationProvider { VoiceIceConfiguration Create(Guid membershipId); }

internal sealed class VoiceIceConfigurationProvider(IOptions<VoiceIceOptions> options, TimeProvider timeProvider) : IVoiceIceConfigurationProvider
{
    public VoiceIceConfiguration Create(Guid membershipId)
    {
        var configured = options.Value;
        var lifetime = TimeSpan.FromMinutes(Math.Clamp(configured.CredentialLifetimeMinutes, 5, 1440));
        var expires = timeProvider.GetUtcNow().Add(lifetime);
        var servers = new List<VoiceIceServer>();
        if (configured.StunUrls.Length > 0) servers.Add(new VoiceIceServer(configured.StunUrls, null, null));
        if (configured.TurnUrls.Length > 0 && !string.IsNullOrWhiteSpace(configured.TurnRestSecret))
        {
            var username = $"{expires.ToUnixTimeSeconds()}:{membershipId:N}";
            using var hmac = new HMACSHA1(Encoding.UTF8.GetBytes(configured.TurnRestSecret));
            var credential = Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(username)));
            servers.Add(new VoiceIceServer(configured.TurnUrls, username, credential));
        }
        return new VoiceIceConfiguration(servers, expires);
    }
}
