using BotGlobal.Games.Domain.Xo;
using BotGlobal.Games.Realtime.Voice;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Games;

public sealed class VoiceRuntimeTests
{
    [Fact]
    public void Classic_ruleset_enables_generic_voice_capability() => Assert.True(XoRuleset.Classic.VoiceEnabled);

    [Fact]
    public void Registry_rejects_stale_generation_and_resolves_only_session_peer()
    {
        var registry = new VoiceConnectionRegistry();
        var session = Guid.NewGuid();
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var outsider = Guid.NewGuid();
        var one = registry.Join("one", session, first, 1, true).Current;
        registry.Join("outsider", Guid.NewGuid(), outsider, 1, true);
        var two = registry.Join("two", session, second, 4, false);

        Assert.Equal(first, two.Peer!.MembershipId);
        Assert.Equal(second, registry.PeerOf(one)!.MembershipId);
        Assert.Throws<InvalidOperationException>(() => registry.RequireCurrent("two", session, second, 3));
        Assert.Equal(4, registry.RequireCurrent("two", session, second, 4).Generation);
    }

    [Fact]
    public void Duplicate_join_replaces_old_connection_and_leave_cleans_presence()
    {
        var registry = new VoiceConnectionRegistry();
        var session = Guid.NewGuid();
        var member = Guid.NewGuid();
        registry.Join("old", session, member, 1, true);
        registry.Join("new", session, member, 2, true);

        Assert.Null(registry.Leave("old"));
        Assert.NotNull(registry.Leave("new"));
    }

    [Fact]
    public void Two_distinct_participants_resolve_each_other_as_exact_inverse_peers()
    {
        var registry = new VoiceConnectionRegistry();
        var session = Guid.NewGuid();
        var memberA = Guid.NewGuid();
        var memberB = Guid.NewGuid();
        var participantA = registry.Join("connection-a", session, memberA, 1, true).Current;
        var participantB = registry.Join("connection-b", session, memberB, 1, false).Current;

        var peerOfA = Assert.IsType<VoiceConnectionRegistry.Participant>(registry.PeerOf(participantA));
        var peerOfB = Assert.IsType<VoiceConnectionRegistry.Participant>(registry.PeerOf(participantB));
        Assert.Equal((memberB, "connection-b"), (peerOfA.MembershipId, peerOfA.ConnectionId));
        Assert.Equal((memberA, "connection-a"), (peerOfB.MembershipId, peerOfB.ConnectionId));
    }

    [Theory]
    [InlineData("offer")]
    [InlineData("answer")]
    [InlineData("ICE candidate")]
    public void Signaling_route_can_never_return_to_sender(string operation)
    {
        var registry = new VoiceConnectionRegistry();
        var session = Guid.NewGuid();
        var sender = registry.Join("sender-connection", session, Guid.NewGuid(), 1, true).Current;

        Assert.Null(registry.PeerOf(sender));

        var receiver = registry.Join("receiver-connection", session, Guid.NewGuid(), 1, false).Current;
        var target = Assert.IsType<VoiceConnectionRegistry.Participant>(registry.PeerOf(sender));
        Assert.NotEqual(sender.ConnectionId, target.ConnectionId);
        Assert.NotEqual(sender.MembershipId, target.MembershipId);
        Assert.Equal(receiver, target);
        Assert.False(string.IsNullOrWhiteSpace(operation));
    }

    [Fact]
    public void Two_connections_for_one_membership_never_resolve_that_membership_as_remote_peer()
    {
        var registry = new VoiceConnectionRegistry();
        var session = Guid.NewGuid();
        var localMembership = Guid.NewGuid();
        registry.Join("local-old", session, localMembership, 1, true);
        var localCurrent = registry.Join("local-current", session, localMembership, 2, true).Current;

        Assert.Null(registry.PeerOf(localCurrent));
        Assert.Throws<InvalidOperationException>(() => registry.RequireCurrent("local-old", session, localMembership, 1));
    }

    [Fact]
    public void Reconnect_and_rejoin_preserve_inverse_opponent_mapping()
    {
        var registry = new VoiceConnectionRegistry();
        var session = Guid.NewGuid();
        var memberA = Guid.NewGuid();
        var memberB = Guid.NewGuid();
        var oldA = registry.Join("a-old", session, memberA, 1, true).Current;
        var participantB = registry.Join("b", session, memberB, 1, false).Current;
        var currentA = registry.Join("a-new", session, memberA, 2, true).Current;

        Assert.Throws<InvalidOperationException>(() => registry.RequireCurrent(oldA.ConnectionId, session, memberA, 1));
        Assert.Equal(participantB, registry.PeerOf(currentA));
        Assert.Equal(currentA, registry.PeerOf(participantB));
    }

    [Fact]
    public void Ice_provider_returns_short_lived_turn_rest_credentials_without_exposing_secret()
    {
        var clock = new FixedTimeProvider(new DateTimeOffset(2026, 8, 29, 12, 0, 0, TimeSpan.Zero));
        var provider = new VoiceIceConfigurationProvider(
            Options.Create(new VoiceIceOptions {
                StunUrls = ["stun:stun.example.test:3478"],
                TurnUrls = ["turn:voice.example.test:3478?transport=udp", "turns:voice.example.test:443?transport=tcp"],
                TurnRestSecret = "server-side-test-secret",
                CredentialLifetimeMinutes = 30,
            }), clock);

        var result = provider.Create(Guid.Parse("10000000-0000-0000-0000-000000000001"));

        Assert.Equal(clock.GetUtcNow().AddMinutes(30), result.ExpiresAtUtc);
        var turn = Assert.Single(result.Servers, x => x.Username is not null);
        Assert.StartsWith(result.ExpiresAtUtc.ToUnixTimeSeconds().ToString(), turn.Username);
        Assert.NotEqual("server-side-test-secret", turn.Credential);
        Assert.DoesNotContain("server-side-test-secret", System.Text.Json.JsonSerializer.Serialize(result));
    }

    [Fact]
    public void Voice_request_is_idempotent_and_targets_distinct_opponent()
    {
        var registry = new VoiceConsentRegistry();
        var now = DateTimeOffset.UtcNow;
        var sender = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var session = Guid.NewGuid();
        var first = registry.RequestVoice(session, 1, sender, recipient, now, TimeSpan.FromSeconds(30));
        var duplicate = registry.RequestVoice(session, 1, sender, recipient, now.AddSeconds(1), TimeSpan.FromSeconds(30));

        Assert.True(first.Created);
        Assert.False(duplicate.Created);
        Assert.Equal(first.Request.RequestId, duplicate.Request.RequestId);
        Assert.NotEqual(first.Request.RequesterMembershipId, first.Request.RecipientMembershipId);
        Assert.Throws<InvalidOperationException>(() =>
            registry.RequestVoice(session, 1, sender, sender, now, TimeSpan.FromSeconds(30)));
    }

    [Fact]
    public void Recipient_accept_is_idempotent_and_stale_decline_cannot_override_acceptance()
    {
        var registry = new VoiceConsentRegistry();
        var now = DateTimeOffset.UtcNow;
        var sender = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var session = Guid.NewGuid();
        var request = registry.RequestVoice(session, 2, sender, recipient, now, TimeSpan.FromSeconds(30)).Request;

        var accepted = registry.Accept(request.RequestId, session, 2, recipient, now.AddSeconds(1));
        Assert.Equal(accepted, registry.Accept(request.RequestId, session, 2, recipient, now.AddSeconds(2)));
        Assert.Throws<InvalidOperationException>(() =>
            registry.Decline(request.RequestId, session, 2, recipient, now.AddSeconds(3)));
        Assert.Equal(VoiceConsentRegistry.Status.Accepted, registry.RequireAccepted(session, 2, sender).Status);
    }

    [Fact]
    public void Decline_and_timeout_never_create_accepted_voice_consent()
    {
        var registry = new VoiceConsentRegistry();
        var now = DateTimeOffset.UtcNow;
        var sender = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var session = Guid.NewGuid();
        var declined = registry.RequestVoice(session, 1, sender, recipient, now, TimeSpan.FromSeconds(5)).Request;
        registry.Decline(declined.RequestId, session, 1, recipient, now.AddSeconds(1));
        Assert.Throws<InvalidOperationException>(() => registry.RequireAccepted(session, 1, sender));

        var timeout = registry.RequestVoice(session, 1, sender, recipient, now.AddSeconds(2), TimeSpan.FromSeconds(5)).Request;
        Assert.Null(registry.Expire(timeout.RequestId, now.AddSeconds(6)));
        Assert.Equal(VoiceConsentRegistry.Status.TimedOut, registry.Expire(timeout.RequestId, now.AddSeconds(8))!.Status);
        Assert.Throws<InvalidOperationException>(() => registry.RequireAccepted(session, 1, recipient));
    }

    [Fact]
    public void Prior_match_request_is_rejected_after_rematch()
    {
        var registry = new VoiceConsentRegistry();
        var now = DateTimeOffset.UtcNow;
        var sender = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var session = Guid.NewGuid();
        var request = registry.RequestVoice(session, 1, sender, recipient, now, TimeSpan.FromSeconds(30)).Request;

        Assert.Throws<InvalidOperationException>(() =>
            registry.Accept(request.RequestId, session, 2, recipient, now.AddSeconds(1)));
    }

    [Fact]
    public void Accepted_voice_is_idempotent_and_can_be_ended_by_either_participant()
    {
        var registry = new VoiceConsentRegistry();
        var now = DateTimeOffset.UtcNow;
        var sender = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var session = Guid.NewGuid();
        var request = registry.RequestVoice(session, 1, sender, recipient, now, TimeSpan.FromSeconds(30)).Request;
        registry.Accept(request.RequestId, session, 1, recipient, now.AddSeconds(1));

        var duplicate = registry.RequestVoice(session, 1, sender, recipient, now.AddSeconds(2), TimeSpan.FromSeconds(30));
        Assert.False(duplicate.Created);
        Assert.Equal(request.RequestId, duplicate.Request.RequestId);
        Assert.Equal(VoiceConsentRegistry.Status.Ended, registry.End(request.RequestId, session, 1, sender).Status);
        Assert.Throws<InvalidOperationException>(() => registry.RequireAccepted(session, 1, recipient));
    }

    [Fact]
    public void Disconnect_during_accepted_join_ends_stale_consent_and_allows_a_new_request()
    {
        var registry = new VoiceConsentRegistry();
        var now = DateTimeOffset.UtcNow;
        var sender = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var session = Guid.NewGuid();
        var crashed = registry.RequestVoice(session, 1, sender, recipient, now, TimeSpan.FromSeconds(30)).Request;
        registry.Accept(crashed.RequestId, session, 1, recipient, now.AddSeconds(1));

        var cleaned = Assert.Single(registry.Disconnect(session, recipient));
        Assert.Equal(VoiceConsentRegistry.Status.Ended, cleaned.Status);
        Assert.Throws<InvalidOperationException>(() => registry.RequireAccepted(session, 1, sender));

        var retry = registry.RequestVoice(session, 1, sender, recipient, now.AddSeconds(2), TimeSpan.FromSeconds(30));
        Assert.True(retry.Created);
        Assert.NotEqual(crashed.RequestId, retry.Request.RequestId);
    }

    [Fact]
    public void Cancel_is_idempotent_for_stale_request_and_cannot_remove_newer_request()
    {
        var registry = new VoiceConsentRegistry();
        var now = DateTimeOffset.UtcNow;
        var sender = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var session = Guid.NewGuid();
        var stale = registry.RequestVoice(session, 1, sender, recipient, now, TimeSpan.FromSeconds(30)).Request;

        Assert.Equal(VoiceConsentRegistry.Status.Cancelled,
            registry.Cancel(stale.RequestId, session, 1, sender, now.AddSeconds(1)).Status);
        var current = registry.RequestVoice(session, 1, sender, recipient, now.AddSeconds(2), TimeSpan.FromSeconds(30)).Request;

        Assert.Equal(VoiceConsentRegistry.Status.Cancelled,
            registry.Cancel(stale.RequestId, session, 1, sender, now.AddSeconds(3)).Status);
        Assert.Equal(current.RequestId, registry.Current(session, 1, sender, now.AddSeconds(3))!.RequestId);
    }

    [Fact]
    public void Authoritative_consent_state_expires_pending_request_and_never_leaks_to_an_outsider()
    {
        var registry = new VoiceConsentRegistry();
        var now = DateTimeOffset.UtcNow;
        var sender = Guid.NewGuid();
        var recipient = Guid.NewGuid();
        var session = Guid.NewGuid();
        var request = registry.RequestVoice(session, 1, sender, recipient, now, TimeSpan.FromSeconds(5)).Request;

        Assert.Equal(request.RequestId, registry.Current(session, 1, recipient, now.AddSeconds(1))!.RequestId);
        Assert.Throws<InvalidOperationException>(() => registry.Current(session, 1, Guid.NewGuid(), now.AddSeconds(1)));
        Assert.Equal(VoiceConsentRegistry.Status.TimedOut,
            registry.Current(session, 1, sender, now.AddSeconds(6))!.Status);

        var retry = registry.RequestVoice(session, 1, sender, recipient, now.AddSeconds(7), TimeSpan.FromSeconds(5));
        Assert.True(retry.Created);
    }

    [Fact]
    public void Game_connection_registry_excludes_all_sender_membership_connections_from_opponent()
    {
        var registry = new BotGlobal.Games.Realtime.GameConnectionRegistry();
        var session = Guid.NewGuid();
        var sender = Guid.NewGuid();
        var opponent = Guid.NewGuid();
        registry.Connected("sender-old", sender);
        registry.Joined("sender-old", session);
        registry.Connected("sender-current", sender);
        registry.Joined("sender-current", session);
        registry.Connected("opponent", opponent);
        registry.Joined("opponent", session);

        Assert.Equal("opponent", registry.ResolveOpponentConnection("sender-current", session, sender, opponent));
        Assert.NotEqual("sender-old", registry.ResolveOpponentConnection("sender-current", session, sender, opponent));
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
