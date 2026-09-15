using System.Net;
using System.Security.Claims;
using System.Text.Encodings.Web;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Endpoints;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Identity;

public sealed class FamilyGamesAccountDeletionEndpointTests
{
    [Theory]
    [InlineData(ApplicationAccountDeletionOutcome.Completed, HttpStatusCode.NoContent)]
    [InlineData(ApplicationAccountDeletionOutcome.Accepted, HttpStatusCode.Accepted)]
    [InlineData(ApplicationAccountDeletionOutcome.RetryableFailure, HttpStatusCode.ServiceUnavailable)]
    public async Task Maps_outcomes_and_passes_only_authenticated_identity(
        ApplicationAccountDeletionOutcome outcome, HttpStatusCode expected)
    {
        var service = new RecordingDeletion(outcome);
        await using var app = Create(service);
        await app.StartAsync();
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Add("Test-Application", BotGlobalApplications.FamilyGames);
        var response = await client.DeleteAsync("/api/mobile/family-games/account?membershipId=untrusted");
        Assert.Equal(expected, response.StatusCode);
        Assert.Equal(TestAuthentication.Member, service.Identity!.MembershipId);
        Assert.Equal(TestAuthentication.User, service.Identity.GlobalUserId);
        Assert.Equal(BotGlobalApplications.FamilyGames, service.Identity.ApplicationKey);
        Assert.False(service.Identity.IsGuest);
    }

    [Theory]
    [InlineData(null, HttpStatusCode.Unauthorized)]
    [InlineData(BotGlobalApplications.Nqrb, HttpStatusCode.Forbidden)]
    public async Task Rejects_unauthenticated_or_other_application_callers(string? application, HttpStatusCode expected)
    {
        var service = new RecordingDeletion(ApplicationAccountDeletionOutcome.Completed);
        await using var app = Create(service);
        await app.StartAsync();
        var client = app.GetTestClient();
        if (application is not null) client.DefaultRequestHeaders.Add("Test-Application", application);
        Assert.Equal(expected, (await client.DeleteAsync("/api/mobile/family-games/account")).StatusCode);
        Assert.Null(service.Identity);
    }

    [Fact]
    public async Task Rejects_authenticated_family_games_guest_without_calling_deletion_service()
    {
        var service = new RecordingDeletion(ApplicationAccountDeletionOutcome.Completed);
        await using var app = Create(service);
        await app.StartAsync();
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Add("Test-Application", BotGlobalApplications.FamilyGames);
        client.DefaultRequestHeaders.Add("Test-Guest", "true");

        var response = await client.DeleteAsync("/api/mobile/family-games/account");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Null(service.Identity);
    }

    private static WebApplication Create(RecordingDeletion service)
    {
        var builder = WebApplication.CreateBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.UseTestServer();
        builder.Services.AddAuthentication("test")
            .AddScheme<AuthenticationSchemeOptions, TestAuthentication>("test", _ => { });
        builder.Services.AddAuthorizationBuilder().AddPolicy(
            ApplicationIdentityPolicies.For(BotGlobalApplications.FamilyGames), policy =>
                policy.RequireAuthenticatedUser().RequireClaim(ApplicationIdentityDefaults.ApplicationKeyClaim,
                    BotGlobalApplications.FamilyGames));
        builder.Services.AddRateLimiter(options => options.AddFixedWindowLimiter(
            IdentityModule.MobileAccountDeletionRateLimitPolicy, limiter =>
            { limiter.PermitLimit = 5; limiter.Window = TimeSpan.FromMinutes(1); }));
        builder.Services.AddSingleton<IApplicationAccountDeletionService>(service);
        var app = builder.Build();
        app.UseAuthentication();
        app.UseAuthorization();
        app.UseRateLimiter();
        app.MapFamilyGamesAccountDeletionEndpoint();
        return app;
    }

    private sealed class RecordingDeletion(ApplicationAccountDeletionOutcome outcome) : IApplicationAccountDeletionService
    {
        public ApplicationIdentityDescriptor? Identity { get; private set; }
        public Task<ApplicationAccountDeletionOutcome> DeleteAsync(ApplicationIdentityDescriptor identity, CancellationToken cancellationToken)
        { Identity = identity; return Task.FromResult(outcome); }
    }

    private sealed class TestAuthentication(IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger, UrlEncoder encoder) : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
    {
        public static readonly Guid Member = Guid.NewGuid();
        public static readonly Guid User = Guid.NewGuid();
        protected override Task<AuthenticateResult> HandleAuthenticateAsync()
        {
            var application = Request.Headers["Test-Application"].ToString();
            if (string.IsNullOrEmpty(application)) return Task.FromResult(AuthenticateResult.NoResult());
            var identity = new ClaimsIdentity(new[] {
                new Claim(ApplicationIdentityDefaults.MembershipIdClaim, Member.ToString()),
                new Claim(ClaimTypes.Sid, User.ToString()),
                new Claim(ClaimTypes.NameIdentifier, "test-subject"),
                new Claim(ApplicationIdentityDefaults.ApplicationKeyClaim, application),
                new Claim(ApplicationIdentityDefaults.GuestClaim,
                    Request.Headers["Test-Guest"].ToString() == "true" ? "true" : "false"),
            }, Scheme.Name);
            return Task.FromResult(AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(identity), Scheme.Name)));
        }
    }
}
