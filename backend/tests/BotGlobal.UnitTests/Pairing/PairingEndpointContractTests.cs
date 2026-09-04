using BotGlobal.Pairing.Application;
using BotGlobal.Pairing;
using BotGlobal.Pairing.Contracts;
using BotGlobal.Pairing.Endpoints;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using BotGlobal.Pairing.Application.PushRegistrations;
using BotGlobal.Pairing.Application.MobileDevices;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Pairing.Application.Profiles;

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
    public void Mobile_enrollment_requires_central_application_identity_and_rate_limit()
    {
        using var app = BuildApp();
        var endpoint = FindEndpoint(app, "/api/mobile/devices/enrollment");

        var policy = Assert.Single(endpoint.Metadata.OfType<AuthorizationPolicy>());
        Assert.Contains(ApplicationIdentityDefaults.Scheme, policy.AuthenticationSchemes);
        Assert.True(policy.Requirements.Count > 0);

        var rateLimit = Assert.Single(
            endpoint.Metadata.OfType<EnableRateLimitingAttribute>());
        Assert.Equal(PairingModule.MobileEnrollmentRateLimitPolicy, rateLimit.PolicyName);
    }

    [Fact]
    public void Profile_publish_requiresDedicatedMachineCapabilityAndRateLimit()
    {
        using var app = BuildApp();
        var endpoint = FindEndpoint(app, "/api/mobile-profile-snapshots");

        AssertCapability(endpoint, PairingCapabilities.PublishProfile);
        var rateLimit = Assert.Single(
            endpoint.Metadata.OfType<EnableRateLimitingAttribute>());
        Assert.Equal(
            PairingModule.MachineProfilePublishRateLimitPolicy,
            rateLimit.PolicyName);
    }

    [Fact]
    public void MyProfileReadRequiresDeviceIdentityAndAcceptsNoTargetIdentifier()
    {
        using var app = BuildApp();
        var endpoint = FindEndpoint(app, "/api/mobile/profile");

        var policy = Assert.Single(
            endpoint.Metadata.OfType<AuthorizationPolicy>());
        Assert.Contains(
            MobileDeviceAuthenticationDefaults.Scheme,
            policy.AuthenticationSchemes);
        Assert.DoesNotContain("{", endpoint.RoutePattern.RawText);

        var properties = typeof(PublishMobileProfileSnapshotRequest)
            .GetProperties()
            .Select(property => property.Name)
            .ToArray();
        Assert.DoesNotContain("ApplicationId", properties);

        Assert.Equal(
            [
                nameof(MobileProfileSnapshotResponse.DisplayName),
                nameof(MobileProfileSnapshotResponse.JobTitle),
                nameof(MobileProfileSnapshotResponse.OrganizationUnit),
                nameof(MobileProfileSnapshotResponse.Version),
                nameof(MobileProfileSnapshotResponse.UpdatedAtUtc)
            ],
            typeof(MobileProfileSnapshotResponse)
                .GetProperties()
                .Select(property => property.Name));
    }

    [Fact]
    public void Machine_authenticated_create_request_accepts_external_subject_identity()
    {
        var properties =
            typeof(CreatePairingChallengeRequest)
                .GetProperties()
                .Select(property => property.Name)
                .ToArray();

        Assert.Equal(
            new[]
            {
                nameof(CreatePairingChallengeRequest.CorrelationReference),
                nameof(CreatePairingChallengeRequest.ExternalSubjectId)
            },
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
        builder.Services.AddScoped<IMobileProfileSnapshotService, FakeMobileProfileSnapshotService>();
        builder.Services.AddScoped<IMobileDeviceEnrollmentService, FakeMobileDeviceEnrollmentService>();
        builder.Services.AddScoped<
            IMobilePushRegistrationService,
            FakeMobilePushRegistrationService>();
        builder.Services.AddScoped<
            BotGlobal.Pairing.Application.AdminDevicePairings.IAdminDevicePairingService,
            FakeAdminDevicePairingService>();
        builder.Services.AddScoped<
            BotGlobal.Contracts.Notifications.IAdministratorDescriptorReader,
            FakeAdministratorDescriptorReader>();
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

    private sealed class FakeMobilePushRegistrationService
        : IMobilePushRegistrationService
    {
        public Task<MobilePushRegistrationResult> RegisterAsync(
            NotificationApplicationContext application,
            Guid deviceId,
            RegisterMobilePushRequest request,
            CancellationToken cancellationToken)
            => Task.FromResult(
                new MobilePushRegistrationResult(
                    deviceId,
                    application.ApplicationId,
                    request.Provider,
                    DateTimeOffset.UtcNow));

        public Task InvalidateAllAsync(
            Guid deviceId,
            CancellationToken cancellationToken)
            => Task.CompletedTask;
    }

    private sealed class FakeMobileProfileSnapshotService
        : IMobileProfileSnapshotService
    {
        public Task<PublishMobileProfileSnapshotResult> PublishAsync(
            Guid platformClientId,
            PublishMobileProfileSnapshotRequest request,
            CancellationToken cancellationToken) =>
            Task.FromResult(
                new PublishMobileProfileSnapshotResult(
                    MobileProfilePublishOutcome.Unchanged,
                    request.Version));

        public Task<MobileProfileSnapshotResponse?> ReadAsync(
            Guid platformClientId,
            string externalSubjectId,
            CancellationToken cancellationToken) =>
            Task.FromResult<MobileProfileSnapshotResponse?>(null);
    }

    private sealed class FakeMobileDeviceEnrollmentService
        : IMobileDeviceEnrollmentService
    {
        public Task<EnrolledMobileDeviceResponse> EnrollAsync(
            string applicationKey,
            string externalSubjectId,
            EnrollMobileDeviceRequest request,
            CancellationToken cancellationToken) =>
            Task.FromResult(
                new EnrolledMobileDeviceResponse(
                    Guid.NewGuid(),
                    "test-device-credential"));
    }

    private sealed class FakeAdminDevicePairingService
        : BotGlobal.Pairing.Application.AdminDevicePairings.IAdminDevicePairingService
    {
        public Task<IReadOnlyList<BotGlobal.Pairing.Application.AdminDevicePairings.AdminDevicePairingListItem>> ListAsync(
            ApplicationAdministrationScope applicationScope,
            CancellationToken cancellationToken)
            => Task.FromResult<IReadOnlyList<BotGlobal.Pairing.Application.AdminDevicePairings.AdminDevicePairingListItem>>(
                []);

        public Task<BotGlobal.Pairing.Application.AdminDevicePairings.AdminDevicePairingDetail?> FindAsync(
            ApplicationAdministrationScope applicationScope,
            Guid deviceId,
            CancellationToken cancellationToken)
            => Task.FromResult<BotGlobal.Pairing.Application.AdminDevicePairings.AdminDevicePairingDetail?>(null);

        public Task<BotGlobal.Pairing.Application.AdminDevicePairings.AdminRevokeDeviceResult> RevokeAsync(
            BotGlobal.Pairing.Application.AdminDevicePairings.AdminRevokeDeviceCommand command,
            CancellationToken cancellationToken)
            => throw new NotSupportedException();
    }

    private sealed class FakeAdministratorDescriptorReader
        : BotGlobal.Contracts.Notifications.IAdministratorDescriptorReader
    {
        public Task<BotGlobal.Contracts.Notifications.AdministratorDescriptor?> FindAsync(
            Guid userId,
            CancellationToken cancellationToken)
            => Task.FromResult<BotGlobal.Contracts.Notifications.AdministratorDescriptor?>(null);
    }

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
