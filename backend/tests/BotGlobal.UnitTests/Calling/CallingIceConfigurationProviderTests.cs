using System.Net;
using System.Text;
using System.Text.Json;
using BotGlobal.Calling.Realtime;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallingIceConfigurationProviderTests
{
    [Fact]
    public async Task Configured_stun_servers_are_returned_without_credentials()
    {
        var provider = CreateProvider(
            new CallingIceOptions {
                StunUrls = ["stun:stun.example.test:3478"],
            });

        var result = await provider.CreateAsync(Guid.NewGuid());

        var stun = Assert.Single(result.Servers);
        Assert.Equal(["stun:stun.example.test:3478"], stun.Urls);
        Assert.Null(stun.Username);
        Assert.Null(stun.Credential);
    }

    [Fact]
    public void Guest_call_ice_configuration_returns_only_configured_stun_servers()
    {
        var provider = CreateProvider(
            new CallingIceOptions {
                StunUrls = ["stun:stun.example.test:3478", "turn:misconfigured.example.test:3478"],
                TurnUrls = ["turn:turn.example.test:3478?transport=udp"],
                TurnRestSecret = "server-side-test-secret",
            });

        var result = provider.CreateStunOnly(Guid.NewGuid());

        var stun = Assert.Single(result.Servers);
        Assert.Equal(["stun:stun.example.test:3478"], stun.Urls);
        Assert.Null(stun.Username);
        Assert.Null(stun.Credential);
    }

    [Fact]
    public async Task Turn_rest_credentials_are_short_lived_and_do_not_expose_the_server_secret()
    {
        var now = new DateTimeOffset(2026, 8, 31, 12, 0, 0, TimeSpan.Zero);
        var provider = CreateProvider(
            new CallingIceOptions {
                TurnUrls = ["turn:calling.example.test:3478?transport=udp"],
                TurnRestSecret = "server-side-test-secret",
                CredentialLifetimeMinutes = 30,
            },
            new FixedTimeProvider(now));

        var result = await provider.CreateAsync(Guid.Parse("10000000-0000-0000-0000-000000000001"));

        Assert.Equal(now.AddMinutes(30), result.ExpiresAtUtc);
        var turn = Assert.Single(result.Servers);
        Assert.StartsWith(result.ExpiresAtUtc.ToUnixTimeSeconds().ToString(), turn.Username);
        Assert.NotEqual("server-side-test-secret", turn.Credential);
        Assert.DoesNotContain("server-side-test-secret", JsonSerializer.Serialize(result));
    }

    [Fact]
    public async Task Cloudflare_turn_request_uses_official_endpoint_and_returns_existing_wire_shape()
    {
        var now = new DateTimeOffset(2026, 9, 30, 18, 0, 0, TimeSpan.Zero);
        HttpMethod? capturedMethod = null;
        string? capturedUri = null;
        string? capturedAuthScheme = null;
        string? capturedAuthParameter = null;
        string? capturedBody = null;
        var provider = CreateProvider(
            CloudflareOptions(
                endpointBaseUrl: "https://rtc.live.cloudflare.com",
                keyId: "test-key-id",
                apiToken: "server-api-token",
                ttlSeconds: 600),
            new FixedTimeProvider(now),
            new StubHttpMessageHandler(async (request, cancellationToken) =>
            {
                capturedMethod = request.Method;
                capturedUri = request.RequestUri?.ToString();
                capturedAuthScheme = request.Headers.Authorization?.Scheme;
                capturedAuthParameter = request.Headers.Authorization?.Parameter;
                capturedBody = await request.Content!.ReadAsStringAsync(cancellationToken);
                return JsonResponse(
                    HttpStatusCode.Created,
                    """
                    {
                      "iceServers": [
                        {
                          "urls": [
                            "stun:stun.cloudflare.com:3478",
                            "turn:turn.cloudflare.com:3478?transport=udp",
                            "turns:turn.cloudflare.com:5349?transport=tcp"
                          ],
                          "username": "ephemeral-user",
                          "credential": "ephemeral-credential"
                        }
                      ]
                    }
                    """);
            }));

        var result = await provider.CreateAsync(Guid.Parse("20000000-0000-0000-0000-000000000001"));

        Assert.Equal(HttpMethod.Post, capturedMethod);
        Assert.Equal(
            "https://rtc.live.cloudflare.com/v1/turn/keys/test-key-id/credentials/generate-ice-servers",
            capturedUri);
        Assert.Equal("Bearer", capturedAuthScheme);
        Assert.Equal("server-api-token", capturedAuthParameter);
        Assert.Equal("""{"ttl":600}""", capturedBody);
        Assert.Equal(now.AddSeconds(600), result.ExpiresAtUtc);
        var server = Assert.Single(result.Servers);
        Assert.Equal(
            [
                "stun:stun.cloudflare.com:3478",
                "turn:turn.cloudflare.com:3478?transport=udp",
                "turns:turn.cloudflare.com:5349?transport=tcp",
            ],
            server.Urls);
        Assert.Equal("ephemeral-user", server.Username);
        Assert.Equal("ephemeral-credential", server.Credential);
        var serialized = JsonSerializer.Serialize(result);
        Assert.DoesNotContain("server-api-token", serialized);
        Assert.DoesNotContain("test-key-id", serialized);
    }

    [Fact]
    public async Task Cloudflare_turn_request_accepts_single_url_response_values()
    {
        var provider = CreateProvider(
            CloudflareOptions(),
            TimeProvider.System,
            new StubHttpMessageHandler((_, _) => Task.FromResult(JsonResponse(
                HttpStatusCode.Created,
                """
                {
                  "iceServers": [
                    {
                      "urls": "turn:turn.cloudflare.com:3478?transport=udp",
                      "username": "ephemeral-user",
                      "credential": "ephemeral-credential"
                    }
                  ]
                }
                """))));

        var result = await provider.CreateAsync(Guid.NewGuid());

        var server = Assert.Single(result.Servers);
        Assert.Equal(["turn:turn.cloudflare.com:3478?transport=udp"], server.Urls);
    }

    [Theory]
    [InlineData("""[]""")]
    [InlineData("""null""")]
    [InlineData("""{}""")]
    [InlineData("""{"iceServers":[]}""")]
    [InlineData("""{"iceServers":[null]}""")]
    [InlineData("""{"iceServers":[["turn:turn.cloudflare.com:3478?transport=udp"]]}""")]
    [InlineData("""{"iceServers":[{"urls":["turn:turn.cloudflare.com:3478?transport=udp"]}]}""")]
    [InlineData("""{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"],"username":"u","credential":"c"}]}""")]
    public async Task Cloudflare_turn_invalid_response_fails_closed(string responseBody)
    {
        var provider = CreateProvider(
            CloudflareOptions(),
            TimeProvider.System,
            new StubHttpMessageHandler((_, _) => Task.FromResult(JsonResponse(HttpStatusCode.Created, responseBody))));

        await Assert.ThrowsAsync<CallingIceConfigurationUnavailableException>(() => provider.CreateAsync(Guid.NewGuid()));
    }

    [Fact]
    public async Task Cloudflare_turn_provider_error_fails_closed_without_stun_fallback()
    {
        var provider = CreateProvider(
            CloudflareOptions(stunUrls: ["stun:configured-fallback.example.test:3478"]),
            TimeProvider.System,
            new StubHttpMessageHandler((_, _) => Task.FromResult(JsonResponse(HttpStatusCode.BadGateway, """{"error":"provider unavailable"}"""))));

        await Assert.ThrowsAsync<CallingIceConfigurationUnavailableException>(() => provider.CreateAsync(Guid.NewGuid()));
    }

    [Fact]
    public async Task Cloudflare_turn_missing_configuration_fails_before_http_request()
    {
        var called = false;
        var options = CloudflareOptions(apiToken: " ");
        var provider = CreateProvider(
            options,
            TimeProvider.System,
            new StubHttpMessageHandler((_, _) =>
            {
                called = true;
                return Task.FromResult(JsonResponse(HttpStatusCode.Created, "{}"));
            }));

        await Assert.ThrowsAsync<CallingIceConfigurationUnavailableException>(() => provider.CreateAsync(Guid.NewGuid()));
        Assert.False(called);
    }

    private static CallingIceConfigurationProvider CreateProvider(
        CallingIceOptions options,
        TimeProvider? timeProvider = null,
        HttpMessageHandler? handler = null) =>
        new(
            Options.Create(options),
            timeProvider ?? TimeProvider.System,
            new HttpClient(handler ?? new StubHttpMessageHandler((_, _) => throw new InvalidOperationException("Unexpected HTTP request."))));

    private static CallingIceOptions CloudflareOptions(
        string endpointBaseUrl = "https://rtc.live.cloudflare.com",
        string keyId = "cloudflare-key-id",
        string apiToken = "cloudflare-api-token",
        int ttlSeconds = 300,
        string[]? stunUrls = null) => new()
    {
        StunUrls = stunUrls ?? [],
        Cloudflare = new CallingCloudflareTurnOptions
        {
            Enabled = true,
            EndpointBaseUrl = endpointBaseUrl,
            KeyId = keyId,
            ApiToken = apiToken,
            CredentialLifetimeSeconds = ttlSeconds,
            RequestTimeoutSeconds = 1,
        },
    };

    private static HttpResponseMessage JsonResponse(HttpStatusCode statusCode, string body) => new(statusCode)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class StubHttpMessageHandler(
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> sendAsync)
        : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) =>
            sendAsync(request, cancellationToken);
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
