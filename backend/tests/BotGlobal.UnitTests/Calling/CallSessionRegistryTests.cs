using BotGlobal.Calling.Realtime;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Calling;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallSessionRegistryTests
{
    [Fact]
    public void Self_call_remains_rejected_by_the_authoritative_registry()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        registry.Connected("caller", caller);

        var error = Assert.Throws<InvalidOperationException>(() =>
            registry.Start("caller", caller.MembershipId));

        Assert.Equal("call_self_not_allowed", error.Message);
    }

    [Fact]
    public void One_active_call_and_application_isolation_are_server_authoritative()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        var otherApplication = Identity("family-games", "Other");
        registry.Connected("caller", caller);
        registry.Connected("callee", callee);
        registry.Connected("other", otherApplication);

        var started = registry.Start("caller", callee.MembershipId);

        Assert.Equal("nqrb", started.Session.ApplicationKey);
        Assert.Throws<InvalidOperationException>(() => registry.Start("caller", callee.MembershipId));
        Assert.Throws<InvalidOperationException>(() => registry.Start("caller", otherApplication.MembershipId));
        Assert.Throws<InvalidOperationException>(() => registry.RequireParticipant("other", started.Session.CallId));
    }

    [Fact]
    public void Only_authorized_participants_can_join_and_generations_are_current()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        var stranger = Identity("nqrb", "Stranger");
        registry.Connected("caller", caller);
        registry.Connected("callee", callee);
        registry.Connected("stranger", stranger);
        var started = registry.Start("caller", callee.MembershipId);

        var first = registry.Join("caller", started.Session.CallId, 1);
        var second = registry.Join("callee", started.Session.CallId, 3);

        Assert.Null(first.Peer);
        Assert.Equal(caller.MembershipId, second.Peer!.MembershipId);
        Assert.Equal(callee.MembershipId, registry.PeerOf(second.Peer)!.MembershipId);
        Assert.Throws<InvalidOperationException>(() =>
            registry.RequireCurrent("caller", started.Session.CallId, 2));
        Assert.Throws<InvalidOperationException>(() =>
            registry.Join("stranger", started.Session.CallId, 1));
    }

    [Fact]
    public void Disconnect_releases_connection_but_preserves_runtime_session_for_rejoin()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        registry.Connected("caller-old", caller);
        registry.Connected("callee", callee);
        var started = registry.Start("caller-old", callee.MembershipId);
        registry.Join("caller-old", started.Session.CallId, 1);
        registry.Join("callee", started.Session.CallId, 1);

        var departed = Assert.Single(registry.Disconnected("caller-old"));
        registry.Connected("caller-new", caller);
        var rejoined = registry.Join("caller-new", started.Session.CallId, 2);

        Assert.Equal(caller.MembershipId, departed.MembershipId);
        Assert.NotNull(rejoined.Peer);
        Assert.Equal(2, rejoined.Current.Generation);
    }

    [Fact]
    public void End_releases_one_active_call_policy_without_persistence()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        registry.Connected("caller", caller);
        registry.Connected("callee", callee);
        var started = registry.Start("caller", callee.MembershipId);

        registry.End("caller", started.Session.CallId);
        var next = registry.Start("caller", callee.MembershipId);

        Assert.NotEqual(started.Session.CallId, next.Session.CallId);
    }

    [Fact]
    public void Disconnect_after_call_end_is_idempotent_and_has_no_peer_notification()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        registry.Connected("caller", caller);
        registry.Connected("callee", callee);
        var started = registry.Start("caller", callee.MembershipId);
        registry.Join("caller", started.Session.CallId, 1);
        registry.Join("callee", started.Session.CallId, 1);

        registry.End("caller", started.Session.CallId);
        var departed = registry.Disconnected("caller");

        Assert.Empty(departed);
    }

    [Fact]
    public void Multiple_live_connections_for_the_same_member_have_a_deterministic_target()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        registry.Connected("caller", caller);
        registry.Connected("callee-b", callee);
        registry.Connected("callee-a", callee);

        var started = registry.Start("caller", callee.MembershipId);

        Assert.Equal("callee-a", started.Callee.ConnectionId);
    }

    private static ApplicationIdentityDescriptor Identity(string application, string name) =>
        new(Guid.NewGuid(), Guid.NewGuid(), Guid.NewGuid().ToString("N"), application, name, false);

    [Fact]
    public void Offline_callee_can_ring_and_answer_is_authoritative_and_idempotent()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        registry.Connected("caller", caller);
        var callee = new CallingParticipantDescriptor(Guid.NewGuid(), "nqrb", "subject", "Callee", true);
        var now = DateTimeOffset.UtcNow;
        var started = registry.Start("caller", callee, now, TimeSpan.FromSeconds(45));
        Assert.Empty(started.CalleeConnections);
        registry.Connected("callee", new ApplicationIdentityDescriptor(callee.MembershipId, Guid.NewGuid(), callee.SubjectId, "nqrb", callee.DisplayName, false));
        registry.RequireIncoming("callee", started.Session.CallId, now);
        Assert.True(registry.Answer("callee", started.Session.CallId, now).Changed);
        Assert.False(registry.Answer("callee", started.Session.CallId, now).Changed);
    }

    [Fact]
    public void Reject_is_authoritative_idempotent_and_prevents_answer_or_join()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        registry.Connected("caller", caller);
        registry.Connected("callee", callee);
        var now = DateTimeOffset.UtcNow;
        var participant = new CallingParticipantDescriptor(
            callee.MembershipId,
            callee.ApplicationKey,
            callee.SubjectId,
            callee.DisplayName,
            true);
        var started = registry.Start("caller", participant, now, TimeSpan.FromSeconds(45));

        Assert.True(registry.Reject("callee", started.Session.CallId, now).Changed);
        Assert.False(registry.Reject("callee", started.Session.CallId, now).Changed);
        Assert.Equal(CallSessionRegistry.CallStatus.Rejected, started.Session.Status);
        Assert.Throws<InvalidOperationException>(() =>
            registry.Answer("callee", started.Session.CallId, now));
        Assert.Throws<InvalidOperationException>(() =>
            registry.Join("callee", started.Session.CallId, 1));
    }

    [Fact]
    public void Expired_offer_cannot_be_answered_or_resurrected()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var calleeIdentity = Identity("nqrb", "Callee");
        registry.Connected("caller", caller);
        registry.Connected("callee", calleeIdentity);
        var callee = new CallingParticipantDescriptor(calleeIdentity.MembershipId, "nqrb", calleeIdentity.SubjectId, "Callee", true);
        var now = DateTimeOffset.UtcNow;
        var started = registry.Start("caller", callee, now, TimeSpan.FromSeconds(1));
        Assert.Single(registry.Expire(now.AddSeconds(2)));
        Assert.Throws<InvalidOperationException>(() => registry.Answer("callee", started.Session.CallId, now.AddSeconds(2)));
    }

    [Fact]
    public void Live_ice_guard_allows_caller_while_ringing_and_callee_only_after_answer()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var calleeIdentity = Identity("nqrb", "Callee");
        registry.Connected("caller", caller);
        var callee = new CallingParticipantDescriptor(
            calleeIdentity.MembershipId,
            calleeIdentity.ApplicationKey,
            calleeIdentity.SubjectId,
            calleeIdentity.DisplayName,
            true);
        var now = DateTimeOffset.UtcNow;
        var started = registry.Start("caller", callee, now, TimeSpan.FromSeconds(45));
        registry.Connected("callee", calleeIdentity);

        registry.RequireLiveIceParticipant("caller", started.Session.CallId, now);
        var beforeAnswer = Assert.Throws<InvalidOperationException>(() =>
            registry.RequireLiveIceParticipant("callee", started.Session.CallId, now));
        registry.Answer("callee", started.Session.CallId, now);
        registry.RequireLiveIceParticipant("callee", started.Session.CallId, now);

        Assert.Equal("call_not_answered", beforeAnswer.Message);
    }

    [Fact]
    public void Live_ice_guard_rejects_ended_rejected_expired_and_unauthorized_calls()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        var stranger = Identity("nqrb", "Stranger");
        var now = DateTimeOffset.UtcNow;
        registry.Connected("caller", caller);
        registry.Connected("callee", callee);
        registry.Connected("stranger", stranger);

        var answered = registry.Start("caller", Participant(callee), now, TimeSpan.FromSeconds(45));
        registry.Answer("callee", answered.Session.CallId, now);
        registry.End("caller", answered.Session.CallId);
        Assert.Equal("call_session_unavailable", Assert.Throws<InvalidOperationException>(() =>
            registry.RequireLiveIceParticipant("caller", answered.Session.CallId, now)).Message);

        var rejected = registry.Start("caller", Participant(callee), now, TimeSpan.FromSeconds(45));
        registry.Reject("callee", rejected.Session.CallId, now);
        Assert.Equal("call_session_unavailable", Assert.Throws<InvalidOperationException>(() =>
            registry.RequireLiveIceParticipant("caller", rejected.Session.CallId, now)).Message);

        var expired = registry.Start("caller", Participant(callee), now, TimeSpan.FromSeconds(1));
        Assert.Equal("call_session_unavailable", Assert.Throws<InvalidOperationException>(() =>
            registry.RequireLiveIceParticipant("caller", expired.Session.CallId, now.AddSeconds(2))).Message);

        var live = registry.Start("caller", Participant(callee), now, TimeSpan.FromSeconds(45));
        Assert.Equal("call_participant_unauthorized", Assert.Throws<InvalidOperationException>(() =>
            registry.RequireLiveIceParticipant("stranger", live.Session.CallId, now)).Message);
    }

    private static CallingParticipantDescriptor Participant(ApplicationIdentityDescriptor identity) =>
        new(identity.MembershipId, identity.ApplicationKey, identity.SubjectId, identity.DisplayName, true);

    [Fact]
    public void Receipt_is_callee_bound_idempotent_and_cannot_resurrect_a_terminal_offer()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        var stranger = Identity("nqrb", "Stranger");
        registry.Connected("caller", caller);
        registry.Connected("callee", callee);
        registry.Connected("stranger", stranger);
        var now = DateTimeOffset.Parse("2026-10-09T09:00:00Z");
        var started = registry.Start("caller", Participant(callee), now, TimeSpan.FromSeconds(45));

        Assert.Equal("call_callee_required", Assert.Throws<InvalidOperationException>(() =>
            registry.ConfirmIncomingReceipt("caller", started.Session.CallId, now)).Message);
        Assert.Equal("call_participant_unauthorized", Assert.Throws<InvalidOperationException>(() =>
            registry.ConfirmIncomingReceipt("stranger", started.Session.CallId, now)).Message);

        var first = registry.ConfirmIncomingReceipt("callee", started.Session.CallId, now.AddSeconds(1));
        var duplicate = registry.ConfirmIncomingReceipt("callee", started.Session.CallId, now.AddSeconds(2));
        Assert.True(first.Changed);
        Assert.False(duplicate.Changed);
        Assert.Equal("presented", started.Session.DeliveryState);
        Assert.Equal(2, started.Session.DeliveryRevision);

        registry.Answer("callee", started.Session.CallId, now.AddSeconds(3));
        Assert.Equal("answered", started.Session.DeliveryState);
        Assert.False(registry.ConfirmIncomingReceipt("callee", started.Session.CallId, now.AddSeconds(4)).Changed);
        Assert.Equal("answered", started.Session.DeliveryState);
    }

    [Fact]
    public void Delivery_projection_is_caller_only_and_preserves_receipt_history_after_expiry()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        var stranger = Identity("nqrb", "Stranger");
        registry.Connected("caller", caller);
        registry.Connected("callee", callee);
        registry.Connected("stranger", stranger);
        var now = DateTimeOffset.Parse("2026-10-09T09:00:00Z");
        var started = registry.Start("caller", Participant(callee), now, TimeSpan.FromSeconds(1));
        registry.ConfirmIncomingReceipt("callee", started.Session.CallId, now.AddMilliseconds(100));

        var status = registry.DeliveryStatus("caller", started.Session.CallId, now.AddSeconds(2));

        Assert.True(status.Terminal);
        Assert.True(status.WasPresented);
        Assert.Equal("expired", status.TerminalReason);
        Assert.Equal("call_caller_required", Assert.Throws<InvalidOperationException>(() =>
            registry.DeliveryStatus("callee", started.Session.CallId, now.AddSeconds(2))).Message);
        Assert.Equal("call_participant_unauthorized", Assert.Throws<InvalidOperationException>(() =>
            registry.DeliveryStatus("stranger", started.Session.CallId, now.AddSeconds(2))).Message);
    }

    [Fact]
    public void Lazy_expiry_is_enqueued_once_and_drained_only_by_the_worker_entrypoint()
    {
        var registry = new CallSessionRegistry();
        var caller = Identity("nqrb", "Caller");
        var callee = Identity("nqrb", "Callee");
        registry.Connected("caller", caller);
        registry.Connected("callee", callee);
        var now = DateTimeOffset.Parse("2026-10-09T09:00:00Z");
        var started = registry.Start("caller", Participant(callee), now, TimeSpan.FromSeconds(1));

        Assert.Equal("call_offer_stale", Assert.Throws<InvalidOperationException>(() =>
            registry.RequireIncoming("callee", started.Session.CallId, now.AddSeconds(2))).Message);

        Assert.Single(registry.Expire(now.AddSeconds(2)));
        Assert.Empty(registry.Expire(now.AddSeconds(3)));
        Assert.Equal(CallSessionRegistry.CallStatus.Expired, started.Session.Status);
        Assert.True(started.Session.DeliveryTerminal);
    }
}
