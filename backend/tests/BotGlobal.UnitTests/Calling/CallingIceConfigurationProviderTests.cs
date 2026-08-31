using BotGlobal.Calling.Realtime;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallingIceConfigurationProviderTests
{
    [Fact]
    public void Configured_stun_servers_are_returned_without_credentials()
    {
        var provider = new CallingIceConfigurationProvider(
            Options.Create(new CallingIceOptions {
                StunUrls = ["stun:stun.example.test:3478"],
            }), TimeProvider.System);

        var result = provider.Create(Guid.NewGuid());

        var stun = Assert.Single(result.Servers);
        Assert.Equal(["stun:stun.example.test:3478"], stun.Urls);
        Assert.Null(stun.Username);
        Assert.Null(stun.Credential);
    }

    [Fact]
    public void Turn_rest_credentials_are_short_lived_and_do_not_expose_the_server_secret()
    {
        var now = new DateTimeOffset(2026, 8, 31, 12, 0, 0, TimeSpan.Zero);
        var provider = new CallingIceConfigurationProvider(
            Options.Create(new CallingIceOptions {
                TurnUrls = ["turn:calling.example.test:3478?transport=udp"],
                TurnRestSecret = "server-side-test-secret",
                CredentialLifetimeMinutes = 30,
            }), new FixedTimeProvider(now));

        var result = provider.Create(Guid.Parse("10000000-0000-0000-0000-000000000001"));

        Assert.Equal(now.AddMinutes(30), result.ExpiresAtUtc);
        var turn = Assert.Single(result.Servers);
        Assert.StartsWith(result.ExpiresAtUtc.ToUnixTimeSeconds().ToString(), turn.Username);
        Assert.NotEqual("server-side-test-secret", turn.Credential);
        Assert.DoesNotContain("server-side-test-secret", System.Text.Json.JsonSerializer.Serialize(result));
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
