using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application.Entitlements;
using BotGlobal.Games.Application.Invitations;
using BotGlobal.Games.Application.Sessions;
using BotGlobal.Games.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Games;

public sealed class GameInvitationServiceTests
{
    [Fact]
    public async Task Creation_returns_opaque_contract_and_persists_only_token_hash()
    {
        await using var fixture = new Fixture();
        var createdSession = await fixture.Sessions.CreateAsync(
            fixture.Host,
            new CreateGameSessionRequest("classic-3x3"),
            CancellationToken.None);

        var result = await fixture.Invitations.CreateAsync(
            fixture.Host,
            createdSession.Value!.SessionId,
            CancellationToken.None);

        Assert.True(result.Succeeded);
        Assert.Equal(201, result.StatusCode);
        Assert.Equal("xo", result.Value!.GameType);
        Assert.Equal(createdSession.Value.SessionId, result.Value.SessionId);
        Assert.Equal(createdSession.Value.JoinCode, result.Value.JoinCode);
        Assert.StartsWith("familygames://invite/", result.Value.DeepLink);
        Assert.Contains(result.Value.InvitationToken, result.Value.DeepLink, StringComparison.Ordinal);
        Assert.DoesNotContain(createdSession.Value.SessionId.ToString(), result.Value.DeepLink, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain(fixture.Host.SubjectId, result.Value.DeepLink, StringComparison.Ordinal);

        var persisted = await fixture.Context.Invitations.SingleAsync();
        Assert.NotEqual(result.Value.InvitationToken, persisted.TokenHash);
        Assert.Equal(GameInvitationService.Hash(result.Value.InvitationToken), persisted.TokenHash);
    }

    [Fact]
    public async Task Valid_invitation_resolves_and_authoritatively_joins_session()
    {
        await using var fixture = new Fixture();
        var invitation = await fixture.CreateInvitationAsync();

        var result = await fixture.Invitations.ResolveAsync(
            fixture.Joiner,
            new ResolveGameInvitationRequest(invitation.InvitationToken),
            CancellationToken.None);

        Assert.True(result.Succeeded);
        Assert.Equal(invitation.InvitationId, result.Value!.InvitationId);
        Assert.Equal(invitation.SessionId, result.Value.Session.SessionId);
        Assert.Contains(result.Value.Session.Players, x => x.MembershipId == fixture.Joiner.MembershipId);
        Assert.NotNull((await fixture.Context.Invitations.SingleAsync()).ConsumedAtUtc);

        var replay = await fixture.Invitations.ResolveAsync(
            fixture.OtherPlayer,
            new ResolveGameInvitationRequest(invitation.InvitationToken),
            CancellationToken.None);
        Assert.Equal("invitation_inactive", replay.ErrorCode);
    }

    [Fact]
    public async Task Expired_invitation_is_rejected_and_revoked()
    {
        await using var fixture = new Fixture();
        var invitation = await fixture.CreateInvitationAsync();
        fixture.Clock.Advance(TimeSpan.FromMinutes(11));

        var result = await fixture.Invitations.ResolveAsync(
            fixture.Joiner,
            new ResolveGameInvitationRequest(invitation.InvitationToken),
            CancellationToken.None);

        Assert.Equal("invitation_expired", result.ErrorCode);
        Assert.Equal(410, result.StatusCode);
        Assert.NotNull((await fixture.Context.Invitations.SingleAsync()).RevokedAtUtc);
    }

    [Fact]
    public async Task Unknown_token_is_rejected()
    {
        await using var fixture = new Fixture();

        var result = await fixture.Invitations.ResolveAsync(
            fixture.Joiner,
            new ResolveGameInvitationRequest("not-a-real-invitation-token"),
            CancellationToken.None);

        Assert.Equal("invitation_invalid", result.ErrorCode);
        Assert.Equal(404, result.StatusCode);
    }

    [Fact]
    public async Task Application_context_is_server_derived_and_cannot_cross_resolve()
    {
        await using var fixture = new Fixture();
        var invitation = await fixture.CreateInvitationAsync();
        var wrongApplication = fixture.Joiner with { ApplicationKey = "another-application" };

        var result = await fixture.Invitations.ResolveAsync(
            wrongApplication,
            new ResolveGameInvitationRequest(invitation.InvitationToken),
            CancellationToken.None);

        Assert.Equal("invitation_wrong_application", result.ErrorCode);
        Assert.Equal(403, result.StatusCode);
    }

    [Fact]
    public async Task Invitation_is_rejected_when_session_is_no_longer_joinable()
    {
        await using var fixture = new Fixture();
        var invitation = await fixture.CreateInvitationAsync();
        var joined = await fixture.Sessions.JoinAsync(
            fixture.OtherPlayer,
            new JoinGameSessionRequest(invitation.JoinCode!),
            CancellationToken.None);
        Assert.True(joined.Succeeded);

        var result = await fixture.Invitations.ResolveAsync(
            fixture.Joiner,
            new ResolveGameInvitationRequest(invitation.InvitationToken),
            CancellationToken.None);

        Assert.Equal("session_not_joinable", result.ErrorCode);
        Assert.Equal(409, result.StatusCode);
    }

    [Fact]
    public async Task Creating_replacement_invitation_revokes_previous_token()
    {
        await using var fixture = new Fixture();
        var first = await fixture.CreateInvitationAsync();

        var second = await fixture.Invitations.CreateAsync(
            fixture.Host,
            first.SessionId,
            CancellationToken.None);
        var firstResolution = await fixture.Invitations.ResolveAsync(
            fixture.Joiner,
            new ResolveGameInvitationRequest(first.InvitationToken),
            CancellationToken.None);

        Assert.True(second.Succeeded);
        Assert.NotEqual(first.InvitationToken, second.Value!.InvitationToken);
        Assert.Equal("invitation_inactive", firstResolution.ErrorCode);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        public Fixture()
        {
            Context = new GamesDbContext(
                new DbContextOptionsBuilder<GamesDbContext>()
                    .UseInMemoryDatabase(Guid.NewGuid().ToString("N"))
                    .Options);
            Clock = new AdjustableTimeProvider(new DateTimeOffset(2026, 8, 28, 10, 0, 0, TimeSpan.Zero));
            var entitlements = new AllowFreeEntitlements();
            Sessions = new GameSessionService(
                Context,
                entitlements,
                new SilentRealtime(),
                new SilentNotifications(),
                Clock,
                NullLogger<GameSessionService>.Instance);
            Invitations = new GameInvitationService(
                Context,
                Sessions,
                entitlements,
                Clock,
                Options.Create(new GameInvitationOptions
                {
                    LifetimeMinutes = 10,
                    DeepLinkBase = "familygames://invite"
                }),
                NullLogger<GameInvitationService>.Instance);
        }

        public GamesDbContext Context { get; }
        public AdjustableTimeProvider Clock { get; }
        public GameSessionService Sessions { get; }
        public GameInvitationService Invitations { get; }
        public ApplicationIdentityDescriptor Host { get; } = Identity("Host");
        public ApplicationIdentityDescriptor Joiner { get; } = Identity("Joiner");
        public ApplicationIdentityDescriptor OtherPlayer { get; } = Identity("Other");

        public async Task<GameInvitationSnapshot> CreateInvitationAsync()
        {
            var session = await Sessions.CreateAsync(
                Host,
                new CreateGameSessionRequest("classic-3x3"),
                CancellationToken.None);
            var invitation = await Invitations.CreateAsync(
                Host,
                session.Value!.SessionId,
                CancellationToken.None);
            return invitation.Value!;
        }

        public ValueTask DisposeAsync() => Context.DisposeAsync();

        private static ApplicationIdentityDescriptor Identity(string displayName) =>
            new(
                Guid.NewGuid(),
                null,
                $"guest:{Guid.NewGuid():N}",
                BotGlobalApplications.FamilyGames,
                displayName,
                true);
    }

    private sealed class AdjustableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset current = now;

        public override DateTimeOffset GetUtcNow() => current;

        public void Advance(TimeSpan duration) => current = current.Add(duration);
    }

    private sealed class AllowFreeEntitlements : IGameEntitlementAuthorizer
    {
        public Task<bool> IsAllowedAsync(Guid membershipId, string? requiredEntitlement, CancellationToken cancellationToken) =>
            Task.FromResult(requiredEntitlement is null);
    }

    private sealed class SilentNotifications : IGameNotificationPublisher
    {
        public Task PublishAsync(GameSemanticNotification notification, CancellationToken cancellationToken) =>
            Task.CompletedTask;
    }

    private sealed class SilentRealtime : IGameRealtimeNotifier
    {
        public Task SessionCreatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerJoinedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerReadyAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task GameStartedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task StateUpdatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task MoveAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerConnectionChangedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task GameCompletedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RematchRequestedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RematchAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
    }
}
