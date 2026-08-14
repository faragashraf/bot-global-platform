using System.Security.Claims;
using BotGlobal.PlatformClients.Application.Authentication;
using BotGlobal.PlatformClients.Application.Security;
using BotGlobal.PlatformClients.Authentication;
using BotGlobal.PlatformClients.Authorization;
using BotGlobal.PlatformClients.Endpoints;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Routing;

namespace BotGlobal.UnitTests.PlatformClients;

public sealed class PlatformClientMachineAuthenticationTests
{
    private static readonly DateTimeOffset Now =
        new(2026, 8, 14, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public async Task Valid_active_credential_authenticates()
    {
        var secretService = new PlatformClientSecretService();
        const string secret = "high-entropy-machine-secret-value";

        var authenticator =
            new PlatformClientAuthenticator(
                new FakeStore(
                    Snapshot(
                        active: true,
                        secretService.Hash(secret),
                        Now.AddHours(1),
                        null,
                        ["pairing:create", "platform-clients:probe"])),
                secretService);

        var result = await authenticator.AuthenticateAsync(
            " CLIENT-A ",
            secret,
            Now);

        Assert.NotNull(result);
        Assert.Equal("client-a", result!.ClientKey);
        Assert.Contains("pairing:create", result.Capabilities);
    }

    [Fact]
    public async Task Wrong_secret_is_rejected()
    {
        var secretService = new PlatformClientSecretService();

        var authenticator =
            new PlatformClientAuthenticator(
                new FakeStore(
                    Snapshot(
                        true,
                        secretService.Hash("correct-secret"),
                        Now.AddHours(1),
                        null,
                        [])),
                secretService);

        Assert.Null(
            await authenticator.AuthenticateAsync(
                "client-a",
                "wrong-secret",
                Now));
    }

    [Fact]
    public async Task Disabled_client_is_rejected()
    {
        var secretService = new PlatformClientSecretService();

        var authenticator =
            new PlatformClientAuthenticator(
                new FakeStore(
                    Snapshot(
                        false,
                        secretService.Hash("secret"),
                        Now.AddHours(1),
                        null,
                        [])),
                secretService);

        Assert.Null(
            await authenticator.AuthenticateAsync(
                "client-a",
                "secret",
                Now));
    }

    [Fact]
    public async Task Expired_or_revoked_credentials_are_rejected()
    {
        var secretService = new PlatformClientSecretService();

        var snapshot =
            new PlatformClientAuthenticationSnapshot(
                Guid.NewGuid(),
                "client-a",
                true,
                [],
                [
                    new(
                        secretService.Hash("secret"),
                        Now.AddMinutes(-1),
                        null),
                    new(
                        secretService.Hash("secret"),
                        Now.AddHours(1),
                        Now.AddMinutes(-1))
                ]);

        var authenticator =
            new PlatformClientAuthenticator(
                new FakeStore(snapshot),
                secretService);

        Assert.Null(
            await authenticator.AuthenticateAsync(
                "client-a",
                "secret",
                Now));
    }

    [Fact]
    public async Task Capability_handler_requires_machine_capability()
    {
        var requirement =
            new PlatformClientCapabilityRequirement(
                "pairing:create");

        var handler =
            new PlatformClientCapabilityHandler();

        var identity = new ClaimsIdentity(
            [
                new Claim(
                    PlatformClientAuthenticationDefaults.CapabilityClaim,
                    "pairing:create")
            ],
            PlatformClientAuthenticationDefaults.Scheme);

        var context =
            new AuthorizationHandlerContext(
                [requirement],
                new ClaimsPrincipal(identity),
                null);

        await handler.HandleAsync(context);

        Assert.True(context.HasSucceeded);
    }

    [Fact]
    public async Task Missing_capability_is_rejected()
    {
        var requirement =
            new PlatformClientCapabilityRequirement(
                "pairing:create");

        var handler =
            new PlatformClientCapabilityHandler();

        var context =
            new AuthorizationHandlerContext(
                [requirement],
                new ClaimsPrincipal(
                    new ClaimsIdentity(
                        [],
                        PlatformClientAuthenticationDefaults.Scheme)),
                null);

        await handler.HandleAsync(context);

        Assert.False(context.HasSucceeded);
    }

    [Fact]
    public void Probe_whoami_requires_probe_capability()
    {
        var builder = WebApplication.CreateBuilder();
        var app = builder.Build();

        app.MapPlatformClientProbeEndpoints();

        var endpoint = Assert.Single(
            ((IEndpointRouteBuilder)app)
                .DataSources
                .SelectMany(dataSource => dataSource.Endpoints)
                .OfType<RouteEndpoint>(),
            endpoint =>
                string.Equals(
                    endpoint.RoutePattern.RawText,
                    "/api/platform-clients/probe/whoami",
                    StringComparison.Ordinal));

        var policy = Assert.Single(
            endpoint.Metadata
                .OfType<AuthorizationPolicy>());

        var requirement = Assert.Single(
            policy.Requirements
                .OfType<PlatformClientCapabilityRequirement>());

        Assert.Equal(
            PlatformClientProbeEndpoints.ProbeCapability,
            requirement.Capability);
    }

    private static PlatformClientAuthenticationSnapshot Snapshot(
        bool active,
        byte[] secretHash,
        DateTimeOffset? expiresAtUtc,
        DateTimeOffset? revokedAtUtc,
        IReadOnlyCollection<string> capabilities)
        => new(
            Guid.NewGuid(),
            "client-a",
            active,
            capabilities,
            [
                new(
                    secretHash,
                    expiresAtUtc,
                    revokedAtUtc)
            ]);

    private sealed class FakeStore(
        PlatformClientAuthenticationSnapshot? snapshot)
        : IPlatformClientAuthenticationStore
    {
        public Task<PlatformClientAuthenticationSnapshot?>
            FindByClientKeyAsync(
                string normalizedClientKey,
                CancellationToken cancellationToken = default)
            => Task.FromResult(
                snapshot is not null
                && snapshot.ClientKey == normalizedClientKey
                    ? snapshot
                    : null);
    }
}
