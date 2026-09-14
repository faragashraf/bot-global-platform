using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Endpoints;
using BotGlobal.Identity.Infrastructure;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.UnitTests.Identity;

public sealed class MobileIdentityProfileEndpointTests
{
    [Fact]
    public async Task Profile_requires_an_authenticated_mobile_session()
    {
        await using var app = await StartAppAsync();

        var response = await app.GetTestClient().GetAsync("/api/mobile/nqrb/identity/profile");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Profile_returns_only_canonical_name_and_email_for_nqrb_session()
    {
        await using var app = await StartAppAsync();
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "nqrb-session");

        var response = await client.GetAsync("/api/mobile/nqrb/identity/profile");

        response.EnsureSuccessStatusCode();
        using var profile = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal(
            ["displayName", "email"],
            profile.RootElement.EnumerateObject().Select(property => property.Name).Order().ToArray());
        Assert.Equal("Canonical Person", profile.RootElement.GetProperty("displayName").GetString());
        Assert.Equal("person@example.test", profile.RootElement.GetProperty("email").GetString());
    }

    [Fact]
    public async Task Wrong_application_session_cannot_read_nqrb_profile()
    {
        await using var app = await StartAppAsync();
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "family-session");

        var response = await client.GetAsync("/api/mobile/nqrb/identity/profile");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task Existing_me_contract_remains_backward_compatible()
    {
        await using var app = await StartAppAsync();
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "nqrb-session");

        var response = await client.GetAsync("/api/mobile/nqrb/identity/me");

        response.EnsureSuccessStatusCode();
        using var identity = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal(
            ["applicationKey", "displayName", "isGuest", "membershipId", "subjectId"],
            identity.RootElement.EnumerateObject().Select(property => property.Name).Order().ToArray());
        Assert.Equal(BotGlobalApplications.Nqrb, identity.RootElement.GetProperty("applicationKey").GetString());
        Assert.Equal("Canonical Person", identity.RootElement.GetProperty("displayName").GetString());
    }

    [Theory]
    [InlineData(SessionRejection.Expired)]
    [InlineData(SessionRejection.Revoked)]
    [InlineData(SessionRejection.InactiveMembership)]
    public async Task Authenticator_rejects_expired_revoked_and_inactive_sessions(SessionRejection rejection)
    {
        await using var db = CreateDb();
        var now = DateTimeOffset.UtcNow;
        var membership = new ApplicationMembership(
            Guid.NewGuid(),
            BotGlobalApplications.Nqrb,
            "user:test",
            "Test Person",
            Guid.NewGuid(),
            false,
            now);
        var session = new MobileApplicationSession(
            Guid.NewGuid(),
            membership.Id,
            MobileApplicationTokenService.Hash("access"),
            MobileApplicationTokenService.Hash("refresh"),
            rejection == SessionRejection.Expired ? now.AddMinutes(-1) : now.AddMinutes(10),
            now.AddDays(1),
            now);
        if (rejection == SessionRejection.Revoked) session.Revoke(now);
        if (rejection == SessionRejection.InactiveMembership) membership.Deactivate();
        db.ApplicationMemberships.Add(membership);
        db.MobileApplicationSessions.Add(session);
        await db.SaveChangesAsync();

        var result = await new MobileApplicationSessionAuthenticator(db, TimeProvider.System)
            .AuthenticateAsync("access", CancellationToken.None);

        Assert.Null(result);
    }

    private static async Task<WebApplication> StartAppAsync()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddRouting();
        builder.Services.AddAuthentication()
            .AddScheme<AuthenticationSchemeOptions, MobileApplicationAuthenticationHandler>(
                ApplicationIdentityDefaults.Scheme,
                _ => { });
        builder.Services.AddAuthorizationBuilder().AddPolicy(
            ApplicationIdentityPolicies.For(BotGlobalApplications.Nqrb),
            policy =>
            {
                policy.AddAuthenticationSchemes(ApplicationIdentityDefaults.Scheme);
                policy.RequireAuthenticatedUser();
                policy.RequireClaim(
                    ApplicationIdentityDefaults.ApplicationKeyClaim,
                    BotGlobalApplications.Nqrb);
            });
        builder.Services.AddSingleton<IMobileApplicationSessionAuthenticator, FixedSessionAuthenticator>();
        builder.Services.AddSingleton<IMobileIdentityProfileReader>(
            new FixedProfileReader(new MobileIdentityProfileResponse("Canonical Person", "person@example.test")));
        builder.Services.AddSingleton<UnusedMobileServices>();
        builder.Services.AddSingleton<IMobileFederatedIdentityService>(services => services.GetRequiredService<UnusedMobileServices>());
        builder.Services.AddSingleton<IMobileIdentityService>(services => services.GetRequiredService<UnusedMobileServices>());
        builder.Services.AddSingleton<IMobileApplicationTokenService>(services => services.GetRequiredService<UnusedMobileServices>());
        builder.Services.AddSingleton<IApplicationAccountDeletionService>(services => services.GetRequiredService<UnusedMobileServices>());
        var app = builder.Build();
        app.UseAuthentication();
        app.UseAuthorization();
        app.MapNqrbMobileIdentityEndpoints();
        await app.StartAsync();
        return app;
    }

    private static IdentityDbContext CreateDb() => new(
        new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString("N"))
            .Options);

    public enum SessionRejection
    {
        Expired,
        Revoked,
        InactiveMembership
    }

    private sealed class FixedSessionAuthenticator : IMobileApplicationSessionAuthenticator
    {
        public Task<AuthenticatedApplicationSession?> AuthenticateAsync(
            string accessToken,
            CancellationToken cancellationToken)
        {
            var applicationKey = accessToken switch
            {
                "nqrb-session" => BotGlobalApplications.Nqrb,
                "family-session" => BotGlobalApplications.FamilyGames,
                _ => null
            };
            if (applicationKey is null)
            {
                return Task.FromResult<AuthenticatedApplicationSession?>(null);
            }

            return Task.FromResult<AuthenticatedApplicationSession?>(new(
                Guid.NewGuid(),
                new ApplicationIdentityDescriptor(
                    Guid.Parse("11111111-1111-1111-1111-111111111111"),
                    Guid.Parse("22222222-2222-2222-2222-222222222222"),
                    "canonical-subject",
                    applicationKey,
                    "Canonical Person",
                    false)));
        }
    }

    private sealed class FixedProfileReader(MobileIdentityProfileResponse response)
        : IMobileIdentityProfileReader
    {
        public Task<MobileIdentityProfileResponse?> ReadAsync(
            ApplicationIdentityDescriptor identity,
            CancellationToken cancellationToken) => Task.FromResult<MobileIdentityProfileResponse?>(response);
    }

    private sealed class UnusedMobileServices :
        IMobileFederatedIdentityService,
        IMobileIdentityService,
        IMobileApplicationTokenService,
        IApplicationAccountDeletionService
    {
        public Task<MobileIdentityResult> AuthenticateAsync(
            string applicationKey,
            MobileFederatedIdentityRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<MobileIdentityResult> ContinueAsGuestAsync(
            string applicationKey,
            MobileGuestRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<MobileIdentityResult> RegisterAsync(
            string applicationKey,
            MobileRegistrationRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<MobileIdentityResult> LoginAsync(
            string applicationKey,
            MobileLoginRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<MobileIdentityResult> UpgradeGuestAsync(
            Guid membershipId,
            MobileRegistrationRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<MobileSessionResponse?> RefreshAsync(
            string applicationKey,
            MobileRefreshRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<IssuedMobileApplicationSession> IssueAsync(
            ApplicationMembership membership,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<IssuedMobileApplicationSession?> RefreshAsync(
            string refreshToken,
            string applicationKey,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task RevokeAccessTokenAsync(
            string accessToken,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<ApplicationAccountDeletionOutcome> DeleteAsync(
            ApplicationIdentityDescriptor identity,
            CancellationToken cancellationToken) => throw new NotSupportedException();
    }
}
