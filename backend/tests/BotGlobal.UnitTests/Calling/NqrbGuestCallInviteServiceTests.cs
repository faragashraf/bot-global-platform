using BotGlobal.Calling.Realtime;
using BotGlobal.Contracts.Mobile;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Calling;

public sealed class NqrbGuestCallInviteServiceTests
{
    [Fact]
    public void Guest_invite_is_one_seat_and_retry_key_is_idempotent()
    {
        var sessions = new CallSessionRegistry();
        var host = Identity("Host");
        sessions.Connected("host", host);
        var service = new NqrbGuestCallInviteService(
            sessions,
            Options.Create(new NqrbGuestCallInviteOptions()),
            TimeProvider.System);
        var invite = service.CreateHostInvite(host);

        var accepted = service.Accept(invite.Capability, "Browser guest", true, "request-1");
        var retry = service.Accept(invite.Capability, "Browser guest", true, "request-1");
        var replay = service.Accept(invite.Capability, "Second guest", true, "request-2");

        Assert.Equal(NqrbGuestCallInviteStatus.Accepted, accepted.Status);
        Assert.Equal(NqrbGuestCallInviteStatus.Accepted, retry.Status);
        Assert.Equal(accepted.Value!.CallId, retry.Value!.CallId);
        Assert.Equal(accepted.Value.GuestAccessToken, retry.Value.GuestAccessToken);
        Assert.True(accepted.Value.IsNew);
        Assert.False(retry.Value.IsNew);
        Assert.Equal(accepted.Value.ExpiresAtUtc, retry.Value.ExpiresAtUtc);
        Assert.Equal(NqrbGuestCallInviteStatus.AlreadyUsed, replay.Status);
        Assert.True(sessions.IsOnline(host.MembershipId, BotGlobalApplications.Nqrb));
    }

    [Fact]
    public void Failed_guest_history_start_releases_the_host_and_invalidates_the_claim()
    {
        var sessions = new CallSessionRegistry();
        var host = Identity("Host");
        sessions.Connected("host", host);
        var service = new NqrbGuestCallInviteService(
            sessions, Options.Create(new NqrbGuestCallInviteOptions()), TimeProvider.System);
        var invite = service.CreateHostInvite(host);
        var accepted = service.Accept(invite.Capability, "Browser guest", true, "request-1").Value!;

        Assert.Equal(accepted.CallId, sessions.RequireGuestInviteSession(
            accepted.CallId, accepted.InviteId, accepted.HostMembershipId).CallId);
        sessions.CancelGuestInvite(accepted.CallId, accepted.InviteId);
        service.Complete(accepted.InviteId);

        Assert.False(sessions.IsLiveCall(accepted.CallId));
        Assert.Equal(NqrbGuestCallInviteStatus.AlreadyUsed,
            service.Accept(invite.Capability, "Browser guest", true, "request-1").Status);
        var newInvite = service.CreateHostInvite(host);
        Assert.Equal(NqrbGuestCallInviteStatus.Accepted,
            service.Accept(newInvite.Capability, "Another guest", true, "request-2").Status);
    }

    [Fact]
    public void Guest_access_token_is_bound_to_the_single_invite_and_call()
    {
        var sessions = new CallSessionRegistry();
        var host = Identity("Host");
        sessions.Connected("host", host);
        var service = new NqrbGuestCallInviteService(
            sessions,
            Options.Create(new NqrbGuestCallInviteOptions()),
            TimeProvider.System);
        var invite = service.CreateHostInvite(host);
        var accepted = service.Accept(invite.Capability, "Browser guest", true, "request-1").Value!;

        var authenticated = service.AuthenticateGuestToken(accepted.GuestAccessToken);

        Assert.NotNull(authenticated);
        Assert.True(service.IsGuestAuthorized(authenticated!.InviteId, authenticated.Identity.MembershipId, authenticated.CallId));
        Assert.False(service.IsGuestAuthorized(authenticated.InviteId, authenticated.Identity.MembershipId, Guid.NewGuid()));
    }

    [Fact]
    public void Revoked_invite_cannot_be_previewed_or_accepted()
    {
        var sessions = new CallSessionRegistry();
        var host = Identity("Host");
        sessions.Connected("host", host);
        var service = new NqrbGuestCallInviteService(
            sessions,
            Options.Create(new NqrbGuestCallInviteOptions()),
            TimeProvider.System);
        var invite = service.CreateHostInvite(host);

        var status = service.RevokeHostInvite(host.MembershipId, invite.InviteId);

        Assert.Equal(NqrbGuestCallInviteStatus.Revoked, status);
        Assert.Equal(NqrbGuestCallInviteStatus.Revoked, service.Preview(invite.Capability).Status);
        Assert.Equal(NqrbGuestCallInviteStatus.Revoked,
            service.Accept(invite.Capability, "Browser guest", true, "request-1").Status);
    }

    [Fact]
    public void Rejected_guest_call_is_replayable_only_to_the_original_guest_request()
    {
        var sessions = new CallSessionRegistry();
        var host = Identity("Host");
        sessions.Connected("host", host);
        var service = new NqrbGuestCallInviteService(
            sessions, Options.Create(new NqrbGuestCallInviteOptions()), TimeProvider.System);
        var invite = service.CreateHostInvite(host);
        var accepted = service.Accept(invite.Capability, "Guest", true, "request-1").Value!;

        sessions.Reject("host", accepted.CallId, DateTimeOffset.UtcNow);
        service.Complete(invite.InviteId);

        Assert.Equal(NqrbGuestCallInviteStatus.Rejected,
            service.Preview(invite.Capability, "request-1").Status);
        Assert.Equal(NqrbGuestCallInviteStatus.AlreadyUsed,
            service.Preview(invite.Capability, "another-request").Status);
        Assert.Equal(NqrbGuestCallInviteStatus.AlreadyUsed,
            service.Preview(invite.Capability).Status);
    }

    [Fact]
    public void Malformed_public_capabilities_do_not_throw_or_create_calls()
    {
        var service = new NqrbGuestCallInviteService(
            new CallSessionRegistry(), Options.Create(new NqrbGuestCallInviteOptions()), TimeProvider.System);

        Assert.Equal(NqrbGuestCallInviteStatus.Invalid, service.Preview(null!).Status);
        Assert.Equal(NqrbGuestCallInviteStatus.Invalid,
            service.Accept("not-a-capability", "Guest", true, "request-1").Status);
        Assert.Null(service.AuthenticateGuestToken("not-a-token"));
    }

    [Fact]
    public void Answered_guest_call_outlives_the_share_link_expiry()
    {
        var clock = new AdjustableTimeProvider(new DateTimeOffset(2026, 10, 1, 18, 0, 0, TimeSpan.Zero));
        var sessions = new CallSessionRegistry();
        var host = Identity("Host");
        sessions.Connected("host", host);
        var service = new NqrbGuestCallInviteService(
            sessions, Options.Create(new NqrbGuestCallInviteOptions()), clock);
        var invite = service.CreateHostInvite(host);
        var accepted = service.Accept(invite.Capability, "Guest", true, "request-1").Value!;
        sessions.Answer("host", accepted.CallId, clock.GetUtcNow());

        clock.Advance(TimeSpan.FromMinutes(16));

        Assert.NotNull(service.AuthenticateGuestToken(accepted.GuestAccessToken));
        Assert.True(service.IsGuestAuthorized(invite.InviteId, accepted.GuestMembershipId, accepted.CallId));
        Assert.False(service.Accept(invite.Capability, "Guest", true, "request-1").Value!.IsNew);
        Assert.Equal(NqrbGuestCallInviteStatus.AlreadyUsed,
            service.Accept(invite.Capability, "Other", true, "request-2").Status);
    }

    private sealed class AdjustableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset current = now;
        public override DateTimeOffset GetUtcNow() => current;
        public void Advance(TimeSpan amount) => current += amount;
    }

    private static ApplicationIdentityDescriptor Identity(string name) =>
        new(Guid.NewGuid(), Guid.NewGuid(), $"user:{Guid.NewGuid():N}", BotGlobalApplications.Nqrb, name, false);
}
