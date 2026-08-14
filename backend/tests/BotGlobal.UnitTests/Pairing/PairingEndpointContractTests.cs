using BotGlobal.Pairing.Application;
using BotGlobal.Pairing;
using BotGlobal.Pairing.Contracts;
using BotGlobal.Pairing.Endpoints;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.UnitTests.Pairing;

public sealed class PairingEndpointContractTests
{
    [Fact]
    public void Create_endpoint_requires_pairing_create_capability()
    {
        using var app = BuildApp();

        var endpoint = FindEndpoint(
            app,
            "/api/pairing/challenges/");

        AssertCapability(
            endpoint,
            PairingCapabilities.Create);

        var rateLimit = Assert.Single(
            endpoint.Metadata.OfType<EnableRateLimitingAttribute>());

        Assert.Equal(
            PairingModule.MachineCreateRateLimitPolicy,
            rateLimit.PolicyName);
    }

    [Fact]
    public void Status_endpoint_requires_pairing_status_capability()
    {
        using var app = BuildApp();

        var endpoint = FindEndpoint(
            app,
            "/api/pairing/challenges/{challengeId:guid}");

        AssertCapability(
            endpoint,
            PairingCapabilities.Status);
    }

    [Fact]
    public void Mobile_claim_endpoint_is_public_and_rate_limited()
    {
        using var app = BuildApp();

        var endpoint = FindEndpoint(
            app,
            "/api/mobile/pairing/claim");

        Assert.Contains(
            endpoint.Metadata,
            metadata => metadata is IAllowAnonymous);

        var rateLimit = Assert.Single(
            endpoint.Metadata.OfType<EnableRateLimitingAttribute>());

        Assert.Equal(
            PairingModule.MobileClaimRateLimitPolicy,
            rateLimit.PolicyName);
    }

    [Fact]
    public void Public_create_request_does_not_accept_owner_or_user_identity()
    {
        var properties =
            typeof(CreatePairingChallengeRequest)
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

        Assert.Equal(
            new[] { nameof(CreatePairingChallengeRequest.CorrelationReference) },
            properties);
    }

    [Fact]
    public void Mobile_claim_request_contains_token_and_bounded_device_only()
    {
        var properties =
            typeof(ClaimPairingChallengeRequest)
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

        Assert.Equal(
            new[]
            {
                nameof(ClaimPairingChallengeRequest.PairingToken),
                nameof(ClaimPairingChallengeRequest.Device)
            },
            properties);

        var deviceProperties =
            typeof(ClaimPairingDeviceRequest)
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

        Assert.Equal(
            new[]
            {
                nameof(ClaimPairingDeviceRequest.Platform),
                nameof(ClaimPairingDeviceRequest.InstallationId),
                nameof(ClaimPairingDeviceRequest.DeviceName),
                nameof(ClaimPairingDeviceRequest.AppVersion)
            },
            deviceProperties);
    }

    private static WebApplication BuildApp()
    {
        var builder = WebApplication.CreateBuilder();
        builder.Services.AddScoped<IPairingChallengeService, FakePairingChallengeService>();
        var app = builder.Build();

        app.MapPairingModule(
            new PairingMachineAuthorizationOptions(
                "platform_client_id",
                capability => new AuthorizationPolicyBuilder("test-machine")
                    .RequireAuthenticatedUser()
                    .AddRequirements(
                        new CapabilityRequirementMarker(capability))
                    .Build()));

        return app;
    }

    private static RouteEndpoint FindEndpoint(
        WebApplication app,
        string route)
        => Assert.Single(
            ((IEndpointRouteBuilder)app)
                .DataSources
                .SelectMany(dataSource => dataSource.Endpoints)
                .OfType<RouteEndpoint>(),
            endpoint => string.Equals(
                endpoint.RoutePattern.RawText,
                route,
                StringComparison.Ordinal));

    private static void AssertCapability(
        RouteEndpoint endpoint,
        string capability)
    {
        var policy = Assert.Single(
            endpoint.Metadata.OfType<AuthorizationPolicy>());

        var requirement = Assert.Single(
            policy.Requirements.OfType<CapabilityRequirementMarker>());

        Assert.Equal(capability, requirement.Capability);
    }

    private sealed record CapabilityRequirementMarker(
        string Capability)
        : IAuthorizationRequirement;

    private sealed class FakePairingChallengeService
        : IPairingChallengeService
    {
        public Task<CreatePairingChallengeResponse> CreateAsync(
            Guid platformClientId,
            CreatePairingChallengeRequest request,
            CancellationToken cancellationToken = default)
            => throw new NotSupportedException();

        public Task<PairingChallengeStatusResponse?> GetStatusAsync(
            Guid platformClientId,
            Guid challengeId,
            CancellationToken cancellationToken = default)
            => throw new NotSupportedException();

        public Task<ClaimPairingChallengeResult> ClaimAsync(
            ClaimPairingChallengeRequest request,
            CancellationToken cancellationToken = default)
            => throw new NotSupportedException();
    }
}
