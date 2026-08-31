using BotGlobal.Calling.Realtime;
using BotGlobal.Contracts.Mobile;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallSessionRegistryTests
{
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
}
