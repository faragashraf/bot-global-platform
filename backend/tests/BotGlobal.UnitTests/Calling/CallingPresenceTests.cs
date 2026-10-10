using BotGlobal.Calling.Application;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallingPresenceTests
{
    [Fact]
    public async Task Fully_covered_disconnected_without_a_wake_route_is_authoritatively_unavailable()
    {
        var callee = Participant();
        var adapter = Adapter(
            new PresenceDecision(PresenceEvidenceState.Disconnected, DateTimeOffset.UtcNow, true, 2),
            new HashSet<Guid>());

        var result = await adapter.EvaluateAsync(Credential(), Caller(), callee, default);

        Assert.True(result.AuthoritativelyUnavailable);
        Assert.Equal(PresenceEvidenceState.Disconnected, result.Evidence.State);
    }

    [Theory]
    [InlineData(PresenceEvidenceState.Unknown, false, false)]
    [InlineData(PresenceEvidenceState.Disconnected, false, false)]
    [InlineData(PresenceEvidenceState.Disconnected, true, true)]
    public async Task Unknown_partial_or_background_wake_evidence_never_hard_denies(
        PresenceEvidenceState state,
        bool fullyCovered,
        bool hasWakeRoute)
    {
        var callee = Participant();
        var adapter = Adapter(
            new PresenceDecision(state, DateTimeOffset.UtcNow, fullyCovered, 1),
            hasWakeRoute ? new HashSet<Guid> { callee.MembershipId } : new HashSet<Guid>());

        var result = await adapter.EvaluateAsync(Credential(), Caller(), callee, default);

        Assert.False(result.AuthoritativelyUnavailable);
        if (hasWakeRoute) Assert.Equal(PresenceEvidenceState.Unknown, result.Evidence.State);
    }

    [Fact]
    public async Task Authorization_is_rechecked_after_presence_lookup()
    {
        var policy = new SequencedPolicy(true, false);
        var adapter = new CallingPresenceAdapter(
            new FixedPresence(new PresenceDecision(PresenceEvidenceState.Disconnected, DateTimeOffset.UtcNow, true, 1)),
            new FixedSessions(),
            policy,
            [new FixedReachability(new HashSet<Guid>())]);

        var result = await adapter.EvaluateAsync(Credential(), Caller(), Participant(), default);

        Assert.False(result.AuthoritativelyUnavailable);
        Assert.Equal("access_changed", result.Evidence.SafeReason);
        Assert.Equal(2, policy.Calls);
    }

    private static CallingPresenceAdapter Adapter(PresenceDecision decision, IReadOnlySet<Guid> reachable) =>
        new(new FixedPresence(decision), new FixedSessions(), new SequencedPolicy(true, true),
            [new FixedReachability(reachable)]);

    private static PresenceConnectionCredential Credential() =>
        new(Guid.NewGuid(), (_, _) => ValueTask.FromResult(true));

    private static ApplicationIdentityDescriptor Caller() =>
        new(Guid.NewGuid(), Guid.NewGuid(), "caller", BotGlobalApplications.Nqrb, "Caller", false);

    private static CallingParticipantDescriptor Participant() =>
        new(Guid.NewGuid(), BotGlobalApplications.Nqrb, "callee", "Callee", true);

    private sealed class FixedPresence(PresenceDecision decision) : IPresenceDecisionReader
    {
        public bool IsEnabled(string applicationKey) => true;
        public Task<PresenceDecision> ObserveAsync(PresenceCounterpartRequest request, CancellationToken cancellationToken) =>
            Task.FromResult(decision);
    }

    private sealed class FixedReachability(IReadOnlySet<Guid> reachable) : ICallingReachabilityResolver
    {
        public Task<IReadOnlySet<Guid>> FindReachableMembershipsAsync(
            string applicationKey,
            IReadOnlyCollection<CallingParticipantDescriptor> participants,
            CancellationToken cancellationToken) => Task.FromResult(reachable);
    }

    private sealed class SequencedPolicy(params bool[] decisions) : IPresenceAccessPolicy
    {
        public int Calls { get; private set; }
        public string ApplicationKey => BotGlobalApplications.Nqrb;
        public Task<bool> CanObserveAsync(Guid actorMembershipId, Guid counterpartMembershipId, CancellationToken cancellationToken)
        {
            var index = Math.Min(Calls++, decisions.Length - 1);
            return Task.FromResult(decisions[index]);
        }
    }

    private sealed class FixedSessions : IPresenceSessionDirectory
    {
        public Task<PresenceValidatedSession?> ValidateConnectionAsync(PresenceConnectionCredential credential, CancellationToken cancellationToken) =>
            Task.FromResult<PresenceValidatedSession?>(null);
        public Task<IReadOnlyList<PresenceSessionAuthority>> ListActiveSessionsAsync(string applicationKey, Guid membershipId, int maximumCount, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<PresenceSessionAuthority>>([]);
        public Task<bool> RevalidateAsync(PresenceSessionAuthority authority, CancellationToken cancellationToken) => Task.FromResult(true);
    }
}
