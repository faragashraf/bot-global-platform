using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
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

    [Fact]
    public async Task Family_games_profile_patch_updates_local_me_while_nqrb_profile_remains_global()
    {
        await using var app = await StartAppAsync();
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "family-session");

        var patch = await client.PatchAsJsonAsync(
            "/api/mobile/family-games/identity/profile",
            new MobileIdentityProfileUpdateRequest("Local LAMMA Name"));

        patch.EnsureSuccessStatusCode();
        var me = await client.GetAsync("/api/mobile/family-games/identity/me");
        me.EnsureSuccessStatusCode();
        using var identity = JsonDocument.Parse(await me.Content.ReadAsStringAsync());
        Assert.Equal("Local LAMMA Name", identity.RootElement.GetProperty("displayName").GetString());

        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "nqrb-session");
        var nqrbProfile = await client.GetAsync("/api/mobile/nqrb/identity/profile");

        nqrbProfile.EnsureSuccessStatusCode();
        using var profile = JsonDocument.Parse(await nqrbProfile.Content.ReadAsStringAsync());
        Assert.Equal("Canonical Person", profile.RootElement.GetProperty("displayName").GetString());
    }

    [Fact]
    public async Task Family_games_profile_patch_rejects_guest_without_global_identity_with_service_validation()
    {
        await using var app = await StartAppAsync();
        var services = app.Services.GetRequiredService<UnusedMobileServices>();
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "family-guest-session");

        var patch = await client.PatchAsJsonAsync(
            "/api/mobile/family-games/identity/profile",
            new MobileIdentityProfileUpdateRequest("Guest Rename"));

        Assert.Equal(HttpStatusCode.BadRequest, patch.StatusCode);
        using var problem = JsonDocument.Parse(await patch.Content.ReadAsStringAsync());
        var identityErrors = problem.RootElement
            .GetProperty("errors")
            .GetProperty("identity")
            .EnumerateArray()
            .Select(error => error.GetString())
            .ToArray();
        Assert.Contains("guest_profile_update_not_supported", identityErrors);
        Assert.Equal("LAMMA Before", services.FamilyGamesDisplayName);
        Assert.Equal(1, services.ProfileUpdateCalls);
        var profileUpdateIdentity = services.ProfileUpdateIdentity;
        Assert.NotNull(profileUpdateIdentity);
        Assert.True(profileUpdateIdentity!.IsGuest);
        Assert.Null(profileUpdateIdentity.GlobalUserId);
        Assert.Equal(BotGlobalApplications.FamilyGames, profileUpdateIdentity.ApplicationKey);
    }

    [Fact]
    public async Task Family_games_profile_patch_rejects_member_missing_global_identity_before_service()
    {
        await using var app = await StartAppAsync();
        var services = app.Services.GetRequiredService<UnusedMobileServices>();
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "family-member-missing-sid");

        var patch = await client.PatchAsJsonAsync(
            "/api/mobile/family-games/identity/profile",
            new MobileIdentityProfileUpdateRequest("Member Rename"));

        Assert.Equal(HttpStatusCode.Unauthorized, patch.StatusCode);
        Assert.Equal("LAMMA Before", services.FamilyGamesDisplayName);
        Assert.Equal(0, services.ProfileUpdateCalls);
        Assert.Null(services.ProfileUpdateIdentity);
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
            }).AddPolicy(
            ApplicationIdentityPolicies.For(BotGlobalApplications.FamilyGames),
            policy =>
            {
                policy.AddAuthenticationSchemes(ApplicationIdentityDefaults.Scheme);
                policy.RequireAuthenticatedUser();
                policy.RequireClaim(
                    ApplicationIdentityDefaults.ApplicationKeyClaim,
                    BotGlobalApplications.FamilyGames);
            });
        builder.Services.AddSingleton<UnusedMobileServices>();
        builder.Services.AddSingleton<IMobileApplicationSessionAuthenticator>(services =>
            new FixedSessionAuthenticator(services.GetRequiredService<UnusedMobileServices>()));
        builder.Services.AddSingleton<IMobileIdentityProfileReader>(
            new FixedProfileReader(new MobileIdentityProfileResponse("Canonical Person", "person@example.test")));
        builder.Services.AddSingleton<IMobileFederatedIdentityService>(services => services.GetRequiredService<UnusedMobileServices>());
        builder.Services.AddSingleton<IMobileIdentityService>(services => services.GetRequiredService<UnusedMobileServices>());
        builder.Services.AddSingleton<IMobileApplicationTokenService>(services => services.GetRequiredService<UnusedMobileServices>());
        builder.Services.AddSingleton<IApplicationAccountDeletionService>(services => services.GetRequiredService<UnusedMobileServices>());
        builder.Services.AddSingleton<IMobileIdentityProfileService>(services => services.GetRequiredService<UnusedMobileServices>());
        var app = builder.Build();
        app.UseAuthentication();
        app.UseAuthorization();
        app.MapNqrbMobileIdentityEndpoints();
        app.MapFamilyGamesMobileIdentityEndpoints();
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

    private sealed class FixedSessionAuthenticator(UnusedMobileServices mobileServices) : IMobileApplicationSessionAuthenticator
    {
        public Task<AuthenticatedApplicationSession?> AuthenticateAsync(
            string accessToken,
            CancellationToken cancellationToken)
        {
            var applicationKey = accessToken switch
            {
                "nqrb-session" => BotGlobalApplications.Nqrb,
                "family-session" => BotGlobalApplications.FamilyGames,
                "family-guest-session" => BotGlobalApplications.FamilyGames,
                "family-member-missing-sid" => BotGlobalApplications.FamilyGames,
                _ => null
            };
            if (applicationKey is null)
            {
                return Task.FromResult<AuthenticatedApplicationSession?>(null);
            }

            var isFamilyGames = applicationKey == BotGlobalApplications.FamilyGames;
            var isGuest = accessToken == "family-guest-session";
            var missingSid = isGuest || accessToken == "family-member-missing-sid";
            return Task.FromResult<AuthenticatedApplicationSession?>(new(
                Guid.NewGuid(),
                new ApplicationIdentityDescriptor(
                    Guid.Parse("11111111-1111-1111-1111-111111111111"),
                    missingSid ? null : Guid.Parse("22222222-2222-2222-2222-222222222222"),
                    isGuest ? "guest-subject" : "canonical-subject",
                    applicationKey,
                    isFamilyGames
                        ? mobileServices.FamilyGamesDisplayName
                        : "Canonical Person",
                    isGuest)));
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
        IMobileIdentityProfileService,
        IMobileApplicationTokenService,
        IApplicationAccountDeletionService
    {
        public string FamilyGamesDisplayName { get; private set; } = "LAMMA Before";
        public int ProfileUpdateCalls { get; private set; }
        public ApplicationIdentityDescriptor? ProfileUpdateIdentity { get; private set; }

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

        public Task<MobileIdentityProfileUpdateResult> UpdateAsync(
            ApplicationIdentityDescriptor identity,
            MobileIdentityProfileUpdateRequest request,
            CancellationToken cancellationToken)
        {
            ProfileUpdateCalls++;
            ProfileUpdateIdentity = identity;
            if (identity.IsGuest)
            {
                return Task.FromResult(MobileIdentityProfileUpdateResult.Failure(
                    "identity",
                    "guest_profile_update_not_supported"));
            }

            FamilyGamesDisplayName = request.DisplayName.Trim();
            return Task.FromResult(MobileIdentityProfileUpdateResult.Success(new MobileIdentityResponse(
                identity.MembershipId,
                identity.SubjectId,
                FamilyGamesDisplayName,
                false,
                BotGlobalApplications.FamilyGames)));
        }

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
