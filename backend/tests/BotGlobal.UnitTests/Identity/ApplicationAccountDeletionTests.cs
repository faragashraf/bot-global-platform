using System.Security.Claims;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Endpoints;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using System.Text.Encodings.Web;
using System.Net;

namespace BotGlobal.UnitTests.Identity;

public sealed class ApplicationAccountDeletionTests
{
    [Fact]
    public void Endpoint_is_authenticated_rate_limited_and_accepts_no_target_identifier()
    {
        var builder = WebApplication.CreateBuilder();
        builder.Services.AddRouting();
        builder.Services.AddAuthorizationBuilder().AddPolicy(
            ApplicationIdentityPolicies.For(BotGlobalApplications.Nqrb),
            policy => policy.RequireAuthenticatedUser());
        builder.Services.AddRateLimiter(options => options.AddFixedWindowLimiter(
            IdentityModule.MobileAccountDeletionRateLimitPolicy,
            limiter =>
            {
                limiter.PermitLimit = 1;
                limiter.Window = TimeSpan.FromMinutes(1);
            }));
        builder.Services.AddSingleton<IApplicationAccountDeletionService, UnusedDeletionService>();
        using var app = builder.Build();
        app.MapNqrbAccountDeletionEndpoint();

        var endpoint = ((IEndpointRouteBuilder)app).DataSources.SelectMany(source => source.Endpoints)
            .OfType<RouteEndpoint>()
            .Single(item => item.RoutePattern.RawText == "/api/mobile/nqrb/account");
        Assert.Equal(HttpMethods.Delete, endpoint.Metadata.GetMetadata<HttpMethodMetadata>()!.HttpMethods.Single());
        Assert.NotNull(endpoint.Metadata.GetMetadata<IAuthorizeData>());
        Assert.Equal(
            IdentityModule.MobileAccountDeletionRateLimitPolicy,
            endpoint.Metadata.GetMetadata<EnableRateLimitingAttribute>()!.PolicyName);
        Assert.DoesNotContain("{", endpoint.RoutePattern.RawText);
    }

    [Fact]
    public async Task Unauthenticated_account_deletion_is_rejected()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddAuthentication("test")
            .AddScheme<AuthenticationSchemeOptions, UnauthenticatedHandler>("test", _ => { });
        builder.Services.AddAuthorizationBuilder().AddPolicy(
            ApplicationIdentityPolicies.For(BotGlobalApplications.Nqrb),
            policy => policy.RequireAuthenticatedUser());
        builder.Services.AddRateLimiter(options => options.AddFixedWindowLimiter(
            IdentityModule.MobileAccountDeletionRateLimitPolicy,
            limiter => limiter.PermitLimit = 1));
        builder.Services.AddSingleton<IApplicationAccountDeletionService, UnusedDeletionService>();
        await using var app = builder.Build();
        app.UseAuthentication();
        app.UseAuthorization();
        app.UseRateLimiter();
        app.MapNqrbAccountDeletionEndpoint();
        await app.StartAsync();

        var response = await app.GetTestClient().DeleteAsync("/api/mobile/nqrb/account");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Deletion_not_yet_at_access_revocation_barrier_returns_retryable_failure()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddAuthentication("test")
            .AddScheme<AuthenticationSchemeOptions, AuthenticatedHandler>("test", _ => { });
        builder.Services.AddAuthorizationBuilder().AddPolicy(
            ApplicationIdentityPolicies.For(BotGlobalApplications.Nqrb),
            policy => policy.RequireAuthenticatedUser());
        builder.Services.AddRateLimiter(options => options.AddFixedWindowLimiter(
            IdentityModule.MobileAccountDeletionRateLimitPolicy,
            limiter =>
            {
                limiter.PermitLimit = 1;
                limiter.Window = TimeSpan.FromMinutes(1);
            }));
        builder.Services.AddSingleton<IApplicationAccountDeletionService>(
            new FixedDeletionService(ApplicationAccountDeletionOutcome.RetryableFailure));
        await using var app = builder.Build();
        app.UseAuthentication();
        app.UseAuthorization();
        app.UseRateLimiter();
        app.MapNqrbAccountDeletionEndpoint();
        await app.StartAsync();

        var response = await app.GetTestClient().DeleteAsync("/api/mobile/nqrb/account");

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
    }

    [Fact]
    public async Task Deletes_nqrb_only_account_and_blocks_all_session_reuse()
    {
        await using var db = CreateDb();
        var fixture = SeedAccount(db, includeOtherMembership: false, includeUnrelatedUser: true);
        var handler = new RecordingHandler { DeviceToInclude = fixture.DeviceId };
        var service = Service(db, handler);

        var result = await service.DeleteAsync(fixture.Identity, CancellationToken.None);

        Assert.Equal(ApplicationAccountDeletionOutcome.Completed, result);
        Assert.Null(await db.Users.FindAsync(fixture.UserId));
        Assert.Empty(await db.ApplicationMemberships.Where(item => item.GlobalUserId == fixture.UserId).ToListAsync());
        Assert.Empty(await db.MobileApplicationSessions.Where(item => item.MembershipId == fixture.MembershipId).ToListAsync());
        Assert.Empty(await db.Set<IdentityUserLogin<Guid>>().Where(item => item.UserId == fixture.UserId).ToListAsync());
        Assert.Empty(await db.AccountDeletionRequests.ToListAsync());
        Assert.NotNull(await db.Users.FindAsync(fixture.UnrelatedUserId));
        Assert.Equal(fixture.MembershipId, Assert.Single(handler.Scopes).Identity.MembershipId);
        Assert.Equal(fixture.DeviceId, Assert.Single(handler.Scopes.Single().MobileDeviceIds));

        Assert.Equal(
            ApplicationAccountDeletionOutcome.Completed,
            await service.DeleteAsync(fixture.Identity, CancellationToken.None));
    }

    [Fact]
    public async Task Preserves_global_user_and_other_application_membership()
    {
        await using var db = CreateDb();
        var fixture = SeedAccount(db, includeOtherMembership: true, includeUnrelatedUser: false);
        var service = Service(db, new RecordingHandler());

        Assert.Equal(
            ApplicationAccountDeletionOutcome.Completed,
            await service.DeleteAsync(fixture.Identity, CancellationToken.None));

        Assert.NotNull(await db.Users.FindAsync(fixture.UserId));
        var remaining = Assert.Single(await db.ApplicationMemberships
            .Where(item => item.GlobalUserId == fixture.UserId).ToListAsync());
        Assert.Equal(BotGlobalApplications.FamilyGames, remaining.ApplicationKey);
        Assert.Single(await db.Set<IdentityUserLogin<Guid>>().Where(item => item.UserId == fixture.UserId).ToListAsync());
        Assert.DoesNotContain(await db.ApplicationMemberships.ToListAsync(), item => item.Id == fixture.MembershipId);
    }

    [Fact]
    public async Task Partial_failure_revokes_access_and_retry_completes_without_second_identity()
    {
        await using var db = CreateDb();
        var fixture = SeedAccount(db, includeOtherMembership: false, includeUnrelatedUser: false);
        var handler = new RecordingHandler { FailuresRemaining = 1 };
        var processor = Processor(db, handler);
        var service = new ApplicationAccountDeletionService(
            db,
            processor,
            TimeProvider.System);

        Assert.Equal(
            ApplicationAccountDeletionOutcome.Accepted,
            await service.DeleteAsync(fixture.Identity, CancellationToken.None));
        Assert.False((await db.ApplicationMemberships.FindAsync(fixture.MembershipId))!.IsActive);
        Assert.All(
            await db.MobileApplicationSessions.Where(item => item.MembershipId == fixture.MembershipId).ToListAsync(),
            session => Assert.NotNull(session.RevokedAtUtc));
        Assert.Single(await db.AccountDeletionRequests.ToListAsync());
        Assert.Null(await new MobileApplicationSessionAuthenticator(db, TimeProvider.System)
            .AuthenticateAsync(fixture.AccessToken, CancellationToken.None));
        Assert.Null(await new MobileApplicationTokenService(db, TimeProvider.System)
            .RefreshAsync(fixture.RefreshToken, BotGlobalApplications.Nqrb, CancellationToken.None));

        var operationId = (await db.AccountDeletionRequests.SingleAsync()).Id;
        Assert.Equal(
            ApplicationAccountDeletionProcessOutcome.Completed,
            await processor.TryProcessAsync(operationId, CancellationToken.None));
        Assert.Null(await db.Users.FindAsync(fixture.UserId));
        Assert.Empty(await db.AccountDeletionRequests.ToListAsync());
    }

    [Fact]
    public async Task Device_ids_discovered_during_revocation_are_persisted_for_retry()
    {
        await using var db = CreateDb();
        var fixture = SeedAccount(db, includeOtherMembership: false, includeUnrelatedUser: false);
        var discoveredDeviceId = Guid.NewGuid();
        var discoverer = new DeviceDiscoveringHandler(discoveredDeviceId);
        var failure = new RecordingHandler { FailuresRemaining = 1 };
        var processor = new ApplicationAccountDeletionProcessor(
            db,
            [discoverer, failure],
            TimeProvider.System,
            NullLogger<ApplicationAccountDeletionProcessor>.Instance);
        var service = new ApplicationAccountDeletionService(
            db,
            processor,
            TimeProvider.System);

        Assert.Equal(
            ApplicationAccountDeletionOutcome.Accepted,
            await service.DeleteAsync(fixture.Identity, CancellationToken.None));
        var operation = await db.AccountDeletionRequests.SingleAsync();
        Assert.Contains(discoveredDeviceId, operation.MobileDeviceIds());

        Assert.Equal(
            ApplicationAccountDeletionProcessOutcome.Completed,
            await processor.TryProcessAsync(operation.Id, CancellationToken.None));
        Assert.Equal(2, discoverer.Calls);
        Assert.Contains(discoveredDeviceId, failure.Scopes.Last().MobileDeviceIds);
    }

    [Fact]
    public async Task Concurrent_processor_before_access_revocation_returns_retryable_failure()
    {
        await using var db = CreateDb();
        var fixture = SeedAccount(db, includeOtherMembership: false, includeUnrelatedUser: false);
        var now = DateTimeOffset.UtcNow;
        var operation = new ApplicationAccountDeletionRequest(
            Guid.NewGuid(),
            fixture.MembershipId,
            fixture.UserId,
            fixture.Identity.ApplicationKey,
            fixture.Identity.SubjectId,
            [],
            now);
        Assert.True(operation.TryAcquireLease(Guid.NewGuid(), now, TimeSpan.FromMinutes(15)));
        db.AccountDeletionRequests.Add(operation);
        await db.SaveChangesAsync();

        var result = await Service(db, new RecordingHandler())
            .DeleteAsync(fixture.Identity, CancellationToken.None);

        Assert.Equal(ApplicationAccountDeletionOutcome.RetryableFailure, result);
        Assert.True((await db.ApplicationMemberships.FindAsync(fixture.MembershipId))!.IsActive);
    }

    [Fact]
    public async Task Access_revocation_handler_failure_is_not_reported_as_safe_accepted_deletion()
    {
        await using var db = CreateDb();
        var fixture = SeedAccount(db, includeOtherMembership: false, includeUnrelatedUser: false);
        var accessHandler = new RecordingHandler
        {
            FailuresRemaining = 1,
            IsAccessRevocation = true
        };

        var result = await Service(db, accessHandler)
            .DeleteAsync(fixture.Identity, CancellationToken.None);

        Assert.Equal(ApplicationAccountDeletionOutcome.RetryableFailure, result);
        var operation = await db.AccountDeletionRequests.SingleAsync();
        Assert.Null(operation.AccessRevokedAtUtc);
        Assert.False((await db.ApplicationMemberships.FindAsync(fixture.MembershipId))!.IsActive);
    }

    [Fact]
    public async Task Concurrent_processor_after_access_revocation_returns_accepted()
    {
        await using var db = CreateDb();
        var fixture = SeedAccount(db, includeOtherMembership: false, includeUnrelatedUser: false);
        var now = DateTimeOffset.UtcNow;
        var operation = new ApplicationAccountDeletionRequest(
            Guid.NewGuid(),
            fixture.MembershipId,
            fixture.UserId,
            fixture.Identity.ApplicationKey,
            fixture.Identity.SubjectId,
            [],
            now);
        operation.MarkAccessRevoked(now);
        Assert.True(operation.TryAcquireLease(Guid.NewGuid(), now, TimeSpan.FromMinutes(15)));
        db.AccountDeletionRequests.Add(operation);
        await db.SaveChangesAsync();

        var result = await Service(db, new RecordingHandler())
            .DeleteAsync(fixture.Identity, CancellationToken.None);

        Assert.Equal(ApplicationAccountDeletionOutcome.Accepted, result);
    }

    [Fact]
    public async Task Expired_processor_lease_is_reclaimed_and_deletion_completes()
    {
        await using var db = CreateDb();
        var fixture = SeedAccount(db, includeOtherMembership: false, includeUnrelatedUser: false);
        var old = DateTimeOffset.UtcNow.AddHours(-1);
        var operation = new ApplicationAccountDeletionRequest(
            Guid.NewGuid(),
            fixture.MembershipId,
            fixture.UserId,
            fixture.Identity.ApplicationKey,
            fixture.Identity.SubjectId,
            [],
            old);
        Assert.True(operation.TryAcquireLease(Guid.NewGuid(), old, TimeSpan.FromMinutes(1)));
        db.AccountDeletionRequests.Add(operation);
        await db.SaveChangesAsync();

        Assert.Equal(
            ApplicationAccountDeletionOutcome.Completed,
            await Service(db, new RecordingHandler()).DeleteAsync(
                fixture.Identity,
                CancellationToken.None));
        Assert.Empty(await db.AccountDeletionRequests.ToListAsync());
    }

    [Fact]
    public void Identity_model_prevents_global_user_removal_while_memberships_exist_and_tracks_processor_concurrency()
    {
        using var db = CreateDb();
        var membership = db.Model.FindEntityType(typeof(ApplicationMembership))!;
        var userForeignKey = Assert.Single(
            membership.GetForeignKeys(),
            key => key.PrincipalEntityType.ClrType == typeof(ApplicationUser));
        Assert.Equal(DeleteBehavior.Restrict, userForeignKey.DeleteBehavior);

        var operation = db.Model.FindEntityType(typeof(ApplicationAccountDeletionRequest))!;
        Assert.True(operation.FindProperty(nameof(ApplicationAccountDeletionRequest.RowVersion))!.IsConcurrencyToken);
    }

    private static ApplicationAccountDeletionService Service(
        IdentityDbContext db,
        IApplicationAccountDeletionHandler handler)
    {
        return new ApplicationAccountDeletionService(
            db,
            Processor(db, handler),
            TimeProvider.System);
    }

    private static ApplicationAccountDeletionProcessor Processor(
        IdentityDbContext db,
        IApplicationAccountDeletionHandler handler) =>
        new(db, [handler], TimeProvider.System, NullLogger<ApplicationAccountDeletionProcessor>.Instance);

    private static IdentityDbContext CreateDb()
    {
        return new IdentityDbContext(
            new DbContextOptionsBuilder<IdentityDbContext>()
                .UseInMemoryDatabase($"identity-deletion-{Guid.NewGuid():N}")
                .Options);
    }

    private static AccountFixture SeedAccount(
        IdentityDbContext db,
        bool includeOtherMembership,
        bool includeUnrelatedUser)
    {
        var now = DateTimeOffset.UtcNow;
        var user = new ApplicationUser(Guid.NewGuid(), "google_subject", "person@example.test", "Person");
        var membership = new ApplicationMembership(
            Guid.NewGuid(), BotGlobalApplications.Nqrb, $"user:{user.Id:N}", user.DisplayName, user.Id, false, now);
        var accessToken = "current-access-token";
        var refreshToken = "current-refresh-token";
        db.Users.Add(user);
        db.Set<IdentityUserLogin<Guid>>().Add(new IdentityUserLogin<Guid>
        {
            UserId = user.Id,
            LoginProvider = "google",
            ProviderKey = $"provider-{user.Id:N}",
            ProviderDisplayName = "google"
        });
        db.ApplicationMemberships.Add(membership);
        db.MobileApplicationSessions.Add(new MobileApplicationSession(
            Guid.NewGuid(), membership.Id, MobileApplicationTokenService.Hash(accessToken),
            MobileApplicationTokenService.Hash(refreshToken), now.AddHours(1), now.AddDays(1), now));
        if (includeOtherMembership)
        {
            db.ApplicationMemberships.Add(new ApplicationMembership(
                Guid.NewGuid(), BotGlobalApplications.FamilyGames, $"user:{user.Id:N}", user.DisplayName, user.Id, false, now));
        }

        var unrelated = new ApplicationUser(Guid.NewGuid(), "unrelated", "other@example.test", "Other");
        if (includeUnrelatedUser) db.Users.Add(unrelated);
        db.SaveChanges();
        return new AccountFixture(
            user.Id,
            membership.Id,
            unrelated.Id,
            Guid.NewGuid(),
            accessToken,
            refreshToken,
            new ApplicationIdentityDescriptor(
                membership.Id, user.Id, membership.SubjectId, membership.ApplicationKey, membership.DisplayName, false));
    }

    private sealed class RecordingHandler : IApplicationAccountDeletionHandler
    {
        public string StepName => "test";
        public int Order => 1;
        public int FailuresRemaining { get; set; }
        public Guid? DeviceToInclude { get; set; }
        public bool IsAccessRevocation { get; set; }
        public bool RevokesAccess => IsAccessRevocation;
        public List<ApplicationAccountDeletionScope> Scopes { get; } = [];

        public Task DeleteAsync(ApplicationAccountDeletionScope scope, CancellationToken cancellationToken)
        {
            if (DeviceToInclude is Guid deviceId) scope.IncludeMobileDeviceIds([deviceId]);
            Scopes.Add(scope);
            if (FailuresRemaining-- > 0) throw new InvalidOperationException("simulated");
            return Task.CompletedTask;
        }
    }

    private sealed class DeviceDiscoveringHandler(Guid deviceId) : IApplicationAccountDeletionHandler
    {
        public string StepName => "discover-device";
        public int Order => 0;
        public int Calls { get; private set; }

        public Task DeleteAsync(ApplicationAccountDeletionScope scope, CancellationToken cancellationToken)
        {
            Calls++;
            scope.IncludeMobileDeviceIds([deviceId]);
            return Task.CompletedTask;
        }
    }

    private sealed class UnusedDeletionService : IApplicationAccountDeletionService
    {
        public Task<ApplicationAccountDeletionOutcome> DeleteAsync(
            ApplicationIdentityDescriptor identity,
            CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class FixedDeletionService(ApplicationAccountDeletionOutcome outcome)
        : IApplicationAccountDeletionService
    {
        public Task<ApplicationAccountDeletionOutcome> DeleteAsync(
            ApplicationIdentityDescriptor identity,
            CancellationToken cancellationToken) => Task.FromResult(outcome);
    }

    private sealed class UnauthenticatedHandler(
        IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger,
        UrlEncoder encoder)
        : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
    {
        protected override Task<AuthenticateResult> HandleAuthenticateAsync() =>
            Task.FromResult(AuthenticateResult.NoResult());
    }

    private sealed class AuthenticatedHandler(
        IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger,
        UrlEncoder encoder)
        : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
    {
        protected override Task<AuthenticateResult> HandleAuthenticateAsync()
        {
            var membershipId = Guid.NewGuid();
            var userId = Guid.NewGuid();
            var identity = new ClaimsIdentity(
                [
                    new Claim(ApplicationIdentityDefaults.MembershipIdClaim, membershipId.ToString()),
                    new Claim(ApplicationIdentityDefaults.ApplicationKeyClaim, BotGlobalApplications.Nqrb),
                    new Claim(ClaimTypes.NameIdentifier, $"user:{userId:N}"),
                    new Claim(ClaimTypes.Sid, userId.ToString()),
                    new Claim(ClaimTypes.Name, "Test user")
                ],
                Scheme.Name);
            return Task.FromResult(AuthenticateResult.Success(
                new AuthenticationTicket(new ClaimsPrincipal(identity), Scheme.Name)));
        }
    }

    private sealed record AccountFixture(
        Guid UserId,
        Guid MembershipId,
        Guid UnrelatedUserId,
        Guid DeviceId,
        string AccessToken,
        string RefreshToken,
        ApplicationIdentityDescriptor Identity);
}
