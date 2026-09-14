using System.Net;
using System.Net.Http.Json;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Endpoints;
using BotGlobal.Identity.Infrastructure;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.UnitTests.Identity;

public sealed class PublicNqrbAccountDeletionTests
{
    [Fact]
    public void Endpoint_is_anonymous_rate_limited_and_has_no_target_identifier()
    {
        var builder = WebApplication.CreateBuilder();
        builder.Services.AddRouting();
        builder.Services.AddRateLimiter(options => options.AddFixedWindowLimiter(
            IdentityModule.PublicNqrbAccountDeletionRateLimitPolicy,
            limiter => limiter.PermitLimit = 1));
        builder.Services.AddSingleton<IPublicNqrbAccountDeletionService, FixedPublicDeletionService>();
        using var app = builder.Build();
        app.MapPublicNqrbAccountDeletionEndpoints();

        var endpoints = ((IEndpointRouteBuilder)app).DataSources.SelectMany(source => source.Endpoints)
            .OfType<RouteEndpoint>()
            .Where(item => item.RoutePattern.RawText?.StartsWith(
                "/api/public/nqrb/account-deletion", StringComparison.Ordinal) == true)
            .ToArray();

        Assert.Equal(2, endpoints.Length);
        Assert.All(endpoints, endpoint =>
        {
            Assert.Equal(HttpMethods.Post, endpoint.Metadata.GetMetadata<HttpMethodMetadata>()!.HttpMethods.Single());
            Assert.NotNull(endpoint.Metadata.GetMetadata<Microsoft.AspNetCore.Authorization.IAllowAnonymous>());
            Assert.Equal(
                IdentityModule.PublicNqrbAccountDeletionRateLimitPolicy,
                endpoint.Metadata.GetMetadata<EnableRateLimitingAttribute>()!.PolicyName);
            Assert.DoesNotContain("{", endpoint.RoutePattern.RawText);
        });
        Assert.Equal(
            [nameof(PublicNqrbAccountDeletionRequest.IdToken)],
            typeof(PublicNqrbAccountDeletionRequest).GetProperties().Select(item => item.Name).ToArray());
    }

    [Fact]
    public async Task Missing_and_invalid_tokens_are_rejected_with_the_same_status()
    {
        await using var app = await StartEndpointAsync(new FixedPublicDeletionService());
        var client = app.GetTestClient();

        var missing = await client.PostAsJsonAsync(
            "/api/public/nqrb/account-deletion",
            new PublicNqrbAccountDeletionRequest(null));
        var invalid = await client.PostAsJsonAsync(
            "/api/public/nqrb/account-deletion",
            new PublicNqrbAccountDeletionRequest("invalid"));

        Assert.Equal(HttpStatusCode.Unauthorized, missing.StatusCode);
        Assert.Equal(missing.StatusCode, invalid.StatusCode);
    }

    [Fact]
    public async Task Verified_identity_and_non_member_receive_the_same_generic_response()
    {
        await using var app = await StartEndpointAsync(new FixedPublicDeletionService());
        var client = app.GetTestClient();

        var verified = await client.PostAsJsonAsync(
            "/api/public/nqrb/account-deletion/verify",
            new PublicNqrbAccountDeletionRequest("member"));
        var member = await client.PostAsJsonAsync(
            "/api/public/nqrb/account-deletion",
            new { idToken = "member", userId = Guid.NewGuid(), email = "other@example.test" });
        var nonMember = await client.PostAsJsonAsync(
            "/api/public/nqrb/account-deletion",
            new { idToken = "non-member", subjectId = "someone-else" });

        Assert.Equal(HttpStatusCode.NoContent, verified.StatusCode);
        Assert.Equal(HttpStatusCode.Accepted, member.StatusCode);
        Assert.Equal(member.StatusCode, nonMember.StatusCode);
        Assert.Empty(await member.Content.ReadAsByteArrayAsync());
        Assert.Empty(await nonMember.Content.ReadAsByteArrayAsync());
    }

    [Fact]
    public async Task Valid_google_subject_resolves_canonical_nqrb_membership_without_creating_state()
    {
        await using var db = CreateDb();
        var targetUserId = Guid.NewGuid();
        var otherUserId = Guid.NewGuid();
        var targetMembership = new ApplicationMembership(
            Guid.NewGuid(), BotGlobalApplications.Nqrb, $"user:{targetUserId:N}", "Target", targetUserId, false,
            DateTimeOffset.UtcNow);
        var otherMembership = new ApplicationMembership(
            Guid.NewGuid(), BotGlobalApplications.Nqrb, $"user:{otherUserId:N}", "Other", otherUserId, false,
            DateTimeOffset.UtcNow);
        db.Users.AddRange(
            new ApplicationUser(targetUserId, "google_target", "target@example.test", "Target"),
            new ApplicationUser(otherUserId, "google_other", "other@example.test", "Other"));
        db.UserLogins.AddRange(
            new IdentityUserLogin<Guid>
            {
                LoginProvider = FederatedIdentityProviders.Google,
                ProviderKey = "verified-target-subject",
                ProviderDisplayName = FederatedIdentityProviders.Google,
                UserId = targetUserId
            },
            new IdentityUserLogin<Guid>
            {
                LoginProvider = FederatedIdentityProviders.Google,
                ProviderKey = "other-subject",
                ProviderDisplayName = FederatedIdentityProviders.Google,
                UserId = otherUserId
            });
        db.ApplicationMemberships.AddRange(targetMembership, otherMembership);
        await db.SaveChangesAsync();
        var deletion = new RecordingDeletionService();
        var service = new PublicNqrbAccountDeletionService(
            db,
            new FixedWebValidator("verified-target-subject"),
            deletion);
        var originalUsers = await db.Users.CountAsync();
        var originalMemberships = await db.ApplicationMemberships.CountAsync();
        var originalSessions = await db.MobileApplicationSessions.CountAsync();

        var result = await service.DeleteAsync(
            new PublicNqrbAccountDeletionRequest("short-lived-google-token"),
            CancellationToken.None);

        Assert.Equal(PublicNqrbAccountDeletionOutcome.Accepted, result);
        var identity = Assert.Single(deletion.Identities);
        Assert.Equal(targetUserId, identity.GlobalUserId);
        Assert.Equal(targetMembership.Id, identity.MembershipId);
        Assert.Equal(targetMembership.SubjectId, identity.SubjectId);
        Assert.Equal(BotGlobalApplications.Nqrb, identity.ApplicationKey);
        Assert.NotEqual(otherUserId, identity.GlobalUserId);
        Assert.Equal(originalUsers, await db.Users.CountAsync());
        Assert.Equal(originalMemberships, await db.ApplicationMemberships.CountAsync());
        Assert.Equal(originalSessions, await db.MobileApplicationSessions.CountAsync());
    }

    [Fact]
    public async Task Valid_google_identity_without_nqrb_membership_is_generic_and_idempotent()
    {
        await using var db = CreateDb();
        var userId = Guid.NewGuid();
        db.Users.Add(new ApplicationUser(userId, "google_other_app", "other-app@example.test", "Other app"));
        db.UserLogins.Add(new IdentityUserLogin<Guid>
        {
            LoginProvider = FederatedIdentityProviders.Google,
            ProviderKey = "other-app-subject",
            ProviderDisplayName = FederatedIdentityProviders.Google,
            UserId = userId
        });
        db.ApplicationMemberships.Add(new ApplicationMembership(
            Guid.NewGuid(), BotGlobalApplications.FamilyGames, $"user:{userId:N}", "Other app", userId, false,
            DateTimeOffset.UtcNow));
        await db.SaveChangesAsync();
        var deletion = new RecordingDeletionService();
        var service = new PublicNqrbAccountDeletionService(
            db,
            new FixedWebValidator("other-app-subject"),
            deletion);

        var first = await service.DeleteAsync(
            new PublicNqrbAccountDeletionRequest("short-lived-google-token"), CancellationToken.None);
        var second = await service.DeleteAsync(
            new PublicNqrbAccountDeletionRequest("short-lived-google-token"), CancellationToken.None);

        Assert.Equal(PublicNqrbAccountDeletionOutcome.Accepted, first);
        Assert.Equal(first, second);
        Assert.Empty(deletion.Identities);
        Assert.Single(await db.Users.ToListAsync());
        Assert.Single(await db.ApplicationMemberships.ToListAsync());
        Assert.Empty(await db.MobileApplicationSessions.ToListAsync());
    }

    private static async Task<WebApplication> StartEndpointAsync(IPublicNqrbAccountDeletionService service)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddRouting();
        builder.Services.AddRateLimiter(options => options.AddFixedWindowLimiter(
            IdentityModule.PublicNqrbAccountDeletionRateLimitPolicy,
            limiter =>
            {
                limiter.PermitLimit = 10;
                limiter.Window = TimeSpan.FromMinutes(1);
            }));
        builder.Services.AddSingleton(service);
        var app = builder.Build();
        app.UseRateLimiter();
        app.MapPublicNqrbAccountDeletionEndpoints();
        await app.StartAsync();
        return app;
    }

    private static IdentityDbContext CreateDb() => new(
        new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase($"public-nqrb-deletion-{Guid.NewGuid():N}")
            .Options);

    private sealed class FixedWebValidator(string subject) : INqrbWebGoogleIdentityValidator
    {
        public Task<FederatedIdentityValidationResult> ValidateAsync(
            string idToken,
            CancellationToken cancellationToken) => Task.FromResult(
                FederatedIdentityValidationResult.Success(new ValidatedFederatedIdentity(
                    FederatedIdentityProviders.Google,
                    subject,
                    "verified@example.test",
                    "Verified")));
    }

    private sealed class RecordingDeletionService : IApplicationAccountDeletionService
    {
        public List<ApplicationIdentityDescriptor> Identities { get; } = [];

        public Task<ApplicationAccountDeletionOutcome> DeleteAsync(
            ApplicationIdentityDescriptor identity,
            CancellationToken cancellationToken)
        {
            Identities.Add(identity);
            return Task.FromResult(ApplicationAccountDeletionOutcome.Completed);
        }
    }

    private sealed class FixedPublicDeletionService : IPublicNqrbAccountDeletionService
    {
        public Task<PublicNqrbAccountDeletionOutcome> VerifyAsync(
            PublicNqrbAccountDeletionRequest request,
            CancellationToken cancellationToken) => Task.FromResult(Outcome(request));

        public Task<PublicNqrbAccountDeletionOutcome> DeleteAsync(
            PublicNqrbAccountDeletionRequest request,
            CancellationToken cancellationToken) => Task.FromResult(Outcome(request));

        private static PublicNqrbAccountDeletionOutcome Outcome(PublicNqrbAccountDeletionRequest request) =>
            string.IsNullOrWhiteSpace(request.IdToken) || request.IdToken == "invalid"
                ? PublicNqrbAccountDeletionOutcome.InvalidGoogleCredential
                : PublicNqrbAccountDeletionOutcome.Accepted;
    }
}
