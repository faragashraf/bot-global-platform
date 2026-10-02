using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Options;

namespace BotGlobal.Calling.Realtime;

public sealed class CallingIceOptions
{
    public const string SectionName = "Calling:Ice";
    public string[] StunUrls { get; set; } = [];
    public string[] TurnUrls { get; set; } = [];
    public string? TurnRestSecret { get; set; }
    public int CredentialLifetimeMinutes { get; set; } = 60;
    public CallingCloudflareTurnOptions Cloudflare { get; set; } = new();
}

public sealed class CallingCloudflareTurnOptions
{
    public bool Enabled { get; set; }
    public string? KeyId { get; set; }
    public string? ApiToken { get; set; }
    public int CredentialLifetimeSeconds { get; set; } = 3600;
    public int RequestTimeoutSeconds { get; set; } = 5;
    public string EndpointBaseUrl { get; set; } = "https://rtc.live.cloudflare.com";
}

public sealed class CallingIceConfigurationUnavailableException : Exception
{
    public CallingIceConfigurationUnavailableException()
        : base("Temporary calling ICE credentials are unavailable.")
    {
    }

    public CallingIceConfigurationUnavailableException(Exception innerException)
        : base("Temporary calling ICE credentials are unavailable.", innerException)
    {
    }
}

public sealed class CallingIceConfigurationProvider(
    IOptions<CallingIceOptions> options,
    TimeProvider timeProvider,
    HttpClient cloudflareHttpClient)
    : IDisposable
{
    private const int MinimumCloudflareCredentialLifetimeSeconds = 60;
    private const int MaximumCloudflareCredentialLifetimeSeconds = 86400;

    public CallingIceConfiguration Create(Guid membershipId)
    {
        var configured = options.Value;
        if (configured.Cloudflare.Enabled) throw new CallingIceConfigurationUnavailableException();
        return CreateLegacyConfiguration(configured, membershipId);
    }

    public CallingIceConfiguration CreateStunOnly(Guid membershipId)
    {
        var configured = options.Value;
        var expires = timeProvider.GetUtcNow().AddMinutes(configured.CredentialLifetimeMinutes);
        var stunUrls = configured.StunUrls
            .Where(url => url.StartsWith("stun:", StringComparison.OrdinalIgnoreCase) ||
                          url.StartsWith("stuns:", StringComparison.OrdinalIgnoreCase))
            .ToArray();
        IReadOnlyList<CallingIceServer> servers = stunUrls.Length > 0
            ? [new CallingIceServer(stunUrls, null, null)]
            : [];
        return new CallingIceConfiguration(servers, expires);
    }

    public async Task<CallingIceConfiguration> CreateAsync(Guid membershipId, CancellationToken cancellationToken = default)
    {
        var configured = options.Value;
        return configured.Cloudflare.Enabled
            ? await CreateCloudflareConfigurationAsync(configured.Cloudflare, cancellationToken)
            : CreateLegacyConfiguration(configured, membershipId);
    }

    private CallingIceConfiguration CreateLegacyConfiguration(CallingIceOptions configured, Guid membershipId)
    {
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

    private async Task<CallingIceConfiguration> CreateCloudflareConfigurationAsync(
        CallingCloudflareTurnOptions cloudflare,
        CancellationToken cancellationToken)
    {
        if (!IsValidCloudflareConfiguration(cloudflare)) throw new CallingIceConfigurationUnavailableException();

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(cloudflare.RequestTimeoutSeconds));

        try
        {
            using var request = new HttpRequestMessage(
                HttpMethod.Post,
                BuildCloudflareRequestUri(cloudflare));
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", cloudflare.ApiToken);
            request.Content = new StringContent(
                $$"""{"ttl":{{cloudflare.CredentialLifetimeSeconds}}}""",
                Encoding.UTF8,
                "application/json");

            using var response = await cloudflareHttpClient.SendAsync(
                request,
                HttpCompletionOption.ResponseHeadersRead,
                timeout.Token);

            if ((int)response.StatusCode != StatusCodes.Status201Created) throw new CallingIceConfigurationUnavailableException();

            await using var body = await response.Content.ReadAsStreamAsync(timeout.Token);
            var servers = await ReadCloudflareIceServersAsync(body, timeout.Token);
            if (servers.Count == 0 || !servers.Any(server => server.Urls.Any(IsTurnUrl)))
                throw new CallingIceConfigurationUnavailableException();

            return new CallingIceConfiguration(
                servers,
                timeProvider.GetUtcNow().AddSeconds(cloudflare.CredentialLifetimeSeconds));
        }
        catch (CallingIceConfigurationUnavailableException)
        {
            throw;
        }
        catch (Exception error) when (error is HttpRequestException or JsonException or TaskCanceledException or OperationCanceledException)
        {
            throw new CallingIceConfigurationUnavailableException(error);
        }
    }

    private static bool IsValidCloudflareConfiguration(CallingCloudflareTurnOptions cloudflare) =>
        cloudflare.Enabled &&
        !string.IsNullOrWhiteSpace(cloudflare.KeyId) &&
        !string.IsNullOrWhiteSpace(cloudflare.ApiToken) &&
        cloudflare.CredentialLifetimeSeconds is >= MinimumCloudflareCredentialLifetimeSeconds and <= MaximumCloudflareCredentialLifetimeSeconds &&
        cloudflare.RequestTimeoutSeconds is >= 1 and <= 30 &&
        Uri.TryCreate(cloudflare.EndpointBaseUrl, UriKind.Absolute, out var endpoint) &&
        (endpoint.Scheme == Uri.UriSchemeHttps || endpoint.IsLoopback);

    private static Uri BuildCloudflareRequestUri(CallingCloudflareTurnOptions cloudflare)
    {
        var endpoint = cloudflare.EndpointBaseUrl.TrimEnd('/');
        var keyId = Uri.EscapeDataString(cloudflare.KeyId!);
        return new Uri($"{endpoint}/v1/turn/keys/{keyId}/credentials/generate-ice-servers", UriKind.Absolute);
    }

    private static async Task<List<CallingIceServer>> ReadCloudflareIceServersAsync(
        Stream body,
        CancellationToken cancellationToken)
    {
        using var document = await JsonDocument.ParseAsync(body, cancellationToken: cancellationToken);
        if (document.RootElement.ValueKind != JsonValueKind.Object ||
            !document.RootElement.TryGetProperty("iceServers", out var iceServers) ||
            iceServers.ValueKind != JsonValueKind.Array)
            throw new CallingIceConfigurationUnavailableException();

        var servers = new List<CallingIceServer>();
        foreach (var iceServer in iceServers.EnumerateArray())
        {
            if (iceServer.ValueKind != JsonValueKind.Object) throw new CallingIceConfigurationUnavailableException();

            var urls = ReadUrls(iceServer);
            if (urls.Count == 0) throw new CallingIceConfigurationUnavailableException();

            var username = ReadOptionalString(iceServer, "username");
            var credential = ReadOptionalString(iceServer, "credential");
            if (urls.Any(IsTurnUrl) && (string.IsNullOrWhiteSpace(username) || string.IsNullOrWhiteSpace(credential)))
                throw new CallingIceConfigurationUnavailableException();

            servers.Add(new CallingIceServer(urls, username, credential));
        }

        return servers;
    }

    private static IReadOnlyList<string> ReadUrls(JsonElement iceServer)
    {
        if (!iceServer.TryGetProperty("urls", out var urls)) return [];
        if (urls.ValueKind == JsonValueKind.String)
        {
            var single = urls.GetString();
            return string.IsNullOrWhiteSpace(single) ? [] : [single];
        }

        if (urls.ValueKind != JsonValueKind.Array) return [];
        return urls.EnumerateArray()
            .Where(url => url.ValueKind == JsonValueKind.String)
            .Select(url => url.GetString())
            .Where(url => !string.IsNullOrWhiteSpace(url))
            .Select(url => url!)
            .ToArray();
    }

    private static string? ReadOptionalString(JsonElement value, string propertyName) =>
        value.TryGetProperty(propertyName, out var property) && property.ValueKind == JsonValueKind.String
            ? property.GetString()
            : null;

    private static bool IsTurnUrl(string url) =>
        url.StartsWith("turn:", StringComparison.OrdinalIgnoreCase) ||
        url.StartsWith("turns:", StringComparison.OrdinalIgnoreCase);

    public void Dispose() => cloudflareHttpClient.Dispose();
}
