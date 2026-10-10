using BotGlobal.Communication.Application.Presence;
using BotGlobal.Contracts.Communication;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Communication;

public sealed class PresenceEngineTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-09T10:00:00Z");

    [Fact]
    public async Task Any_fresh_connected_session_wins_with_complete_multisession_coverage()
    {
        var first = Authority();
        var second = Authority();
        var sessions = new SessionDirectory(first, second);
        var provider = new FakeProvider(new Dictionary<Guid, PresenceProviderSessionSnapshot>
        {
            [first.SessionId] = Snapshot((PresenceEvidenceState.Disconnected, Now.AddSeconds(-4))),
            [second.SessionId] = Snapshot((PresenceEvidenceState.Connected, Now.AddSeconds(-2))),
        });
        var engine = Engine(sessions, provider);

        var result = await engine.ObserveAsync(new PresenceCounterpartRequest(Credential(), first.MembershipId), default);

        Assert.Equal(PresenceEvidenceState.Connected, result.State);
        Assert.True(result.FullyCovered);
        Assert.Equal(2, result.EligibleSessionCount);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Fresh_connected_wins_in_either_device_order_while_incomplete_coverage_is_reported(bool connectedFirst)
    {
        var connected = Authority();
        var missing = Authority();
        var inventory = connectedFirst ? new[] { connected, missing } : new[] { missing, connected };
        var provider = new FakeProvider(new Dictionary<Guid, PresenceProviderSessionSnapshot>
        {
            [connected.SessionId] = Snapshot((PresenceEvidenceState.Connected, Now.AddSeconds(-1))),
            [missing.SessionId] = new(false, false, false, []),
        });
        var engine = Engine(new SessionDirectory(inventory), provider);

        var result = await engine.ObserveAsync(
            new PresenceCounterpartRequest(Credential(), connected.MembershipId), default);

        Assert.Equal(PresenceEvidenceState.Connected, result.State);
        Assert.False(result.FullyCovered);
        Assert.Equal(2, result.EligibleSessionCount);
    }

    [Fact]
    public async Task Missing_malformed_stale_future_or_partially_covered_records_are_unknown()
    {
        var authority = Authority();
        foreach (var snapshot in new[]
        {
            new PresenceProviderSessionSnapshot(false, false, false, []),
            new PresenceProviderSessionSnapshot(true, false, false, []),
            Snapshot((PresenceEvidenceState.Disconnected, Now.AddSeconds(-41))),
            Snapshot((PresenceEvidenceState.Connected, Now.AddSeconds(6))),
            new PresenceProviderSessionSnapshot(true, true, true, []),
        })
        {
            var engine = Engine(new SessionDirectory(authority), new FakeProvider(new Dictionary<Guid, PresenceProviderSessionSnapshot>
            {
                [authority.SessionId] = snapshot,
            }));

            var result = await engine.ObserveAsync(new PresenceCounterpartRequest(Credential(), authority.MembershipId), default);

            Assert.Equal(PresenceEvidenceState.Unknown, result.State);
            Assert.False(result.FullyCovered);
        }
    }

    [Fact]
    public async Task Credential_rotation_with_same_session_id_invalidates_old_inventory_evidence()
    {
        var authority = Authority();
        var sessions = new SessionDirectory(authority) { Revalidate = false };
        var engine = Engine(sessions, new FakeProvider(new Dictionary<Guid, PresenceProviderSessionSnapshot>
        {
            [authority.SessionId] = Snapshot((PresenceEvidenceState.Connected, Now)),
        }));

        var result = await engine.ObserveAsync(new PresenceCounterpartRequest(Credential(), authority.MembershipId), default);

        Assert.Equal(PresenceEvidenceState.Unknown, result.State);
        Assert.Equal("authority_changed", result.SafeReason);
    }

    [Fact]
    public async Task Lease_is_capped_by_current_platform_access_expiry()
    {
        var authority = Authority() with { AccessExpiresAtUtc = Now.AddSeconds(37) };
        var current = new PresenceValidatedSession(authority, "subject", "Person", false);
        var sessions = new SessionDirectory(authority) { Current = current };
        var provider = new FakeProvider(new Dictionary<Guid, PresenceProviderSessionSnapshot>());
        var engine = Engine(sessions, provider);

        var lease = await engine.BootstrapAsync(Credential(authority.SessionId), default);

        Assert.NotNull(lease);
        Assert.Equal(authority.AccessExpiresAtUtc, lease.ExpiresAtUtc);
        Assert.Equal(20, lease.HeartbeatSeconds);
        Assert.Equal(40, lease.FreshnessSeconds);
    }

    [Fact]
    public async Task Bootstrap_revokes_a_provisional_lease_when_authority_changes_during_provider_work()
    {
        var authority = Authority();
        var current = new PresenceValidatedSession(authority, "subject", "Person", false);
        var sessions = new SessionDirectory(authority) { Current = current, Revalidate = false };
        var provider = new FakeProvider(new Dictionary<Guid, PresenceProviderSessionSnapshot>());
        var engine = Engine(sessions, provider);

        var lease = await engine.BootstrapAsync(Credential(authority.SessionId), default);

        Assert.Null(lease);
        Assert.Equal(new[] { "lease" }, provider.Invalidated);
    }

    private static PresenceEngine Engine(SessionDirectory sessions, FakeProvider provider)
    {
        var options = Options.Create(new PresenceOptions());
        var clock = new FixedClock(Now);
        return new PresenceEngine(
            sessions,
            [new AllowPolicy()],
            provider,
            new PresenceLeaseStore(provider, options, clock),
            options,
            clock);
    }

    private static PresenceProviderSessionSnapshot Snapshot(params (PresenceEvidenceState State, DateTimeOffset At)[] records) =>
        new(true, true, false, records.Select(item => new PresenceProviderConnection(item.State, item.At)).ToArray());

    private static PresenceSessionAuthority Authority() => new(
        Guid.NewGuid(),
        SharedMembership,
        Guid.NewGuid(),
        "nqrb",
        Now.AddMinutes(5),
        Guid.NewGuid().ToString("N"));

    private static readonly Guid SharedMembership = Guid.NewGuid();
    private static PresenceConnectionCredential Credential(Guid? sessionId = null) =>
        new(sessionId ?? Guid.NewGuid(), (_, _) => ValueTask.FromResult(true));

    private sealed class FixedClock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }

    private sealed class AllowPolicy : IPresenceAccessPolicy
    {
        public string ApplicationKey => "nqrb";
        public Task<bool> CanObserveAsync(Guid actorMembershipId, Guid counterpartMembershipId, CancellationToken cancellationToken) =>
            Task.FromResult(true);
    }

    private sealed class SessionDirectory(params PresenceSessionAuthority[] inventory) : IPresenceSessionDirectory
    {
        public bool Revalidate { get; set; } = true;
        public PresenceValidatedSession? Current { get; set; } = new(
            Authority() with { MembershipId = Guid.NewGuid() }, "actor", "Actor", false);
        public Task<PresenceValidatedSession?> ValidateConnectionAsync(PresenceConnectionCredential credential, CancellationToken cancellationToken) =>
            Task.FromResult(Current);
        public Task<IReadOnlyList<PresenceSessionAuthority>> ListActiveSessionsAsync(string applicationKey, Guid membershipId, int maximumCount, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<PresenceSessionAuthority>>(inventory);
        public Task<bool> RevalidateAsync(PresenceSessionAuthority authority, CancellationToken cancellationToken) =>
            Task.FromResult(Revalidate);
    }

    private sealed class FakeProvider(IReadOnlyDictionary<Guid, PresenceProviderSessionSnapshot> snapshots) : IPresenceProvider
    {
        public List<string> Invalidated { get; } = [];
        public bool IsEnabled(string applicationKey) => applicationKey == "nqrb";
        public Task<PresenceProviderLease?> CreateLeaseAsync(PresenceValidatedSession session, DateTimeOffset expiresAtUtc, CancellationToken cancellationToken) =>
            Task.FromResult<PresenceProviderLease?>(new(
                "lease", "uid", "connection", "token", "https://demo.firebaseio.test/",
                "presenceConnections/uid/lease/connection", expiresAtUtc));
        public Task<PresenceProviderLease?> RenewLeaseAsync(PresenceValidatedSession session, string leaseId, DateTimeOffset expiresAtUtc, CancellationToken cancellationToken) =>
            CreateLeaseAsync(session, expiresAtUtc, cancellationToken);
        public Task<PresenceProviderSessionSnapshot> ReadSessionAsync(PresenceSessionAuthority authority, CancellationToken cancellationToken) =>
            Task.FromResult(snapshots.TryGetValue(authority.SessionId, out var value)
                ? value
                : new PresenceProviderSessionSnapshot(false, false, false, []));
        public Task InvalidateAsync(PresenceSessionAuthority authority, string leaseId, CancellationToken cancellationToken)
        {
            Invalidated.Add(leaseId);
            return Task.CompletedTask;
        }
    }
}
