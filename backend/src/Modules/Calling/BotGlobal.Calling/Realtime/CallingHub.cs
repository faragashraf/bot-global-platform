using System.Security.Claims;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Calling.Realtime;

[Authorize(AuthenticationSchemes = ApplicationIdentityDefaults.Scheme)]
public sealed class CallingHub(
    CallSessionRegistry sessions,
    CallingIceConfigurationProvider ice,
    ILogger<CallingHub> logger) : Hub
{
    public override Task OnConnectedAsync()
    {
        sessions.Connected(Context.ConnectionId, RequireIdentity());
        return base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        foreach (var departed in sessions.Disconnected(Context.ConnectionId))
        {
            var peer = sessions.PeerOf(departed);
            if (peer is not null)
                await Clients.Client(peer.ConnectionId).SendAsync("CallPeerLeft", PeerEvent(departed, peer));
        }
        if (exception is not null) logger.LogWarning("Calling connection closed with error type {ErrorType}", exception.GetType().Name);
        await base.OnDisconnectedAsync(exception);
    }

    public async Task<StartedCallResult> StartOutgoingCall(StartOutgoingCallRequest request)
    {
        CallSessionRegistry.Started started;
        try { started = sessions.Start(Context.ConnectionId, request.CalleeMembershipId); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        await Clients.Client(started.Callee.ConnectionId).SendAsync("CallOffered",
            new CallOfferedEvent(started.Session.CallId, started.Session.ApplicationKey,
                started.Caller.MembershipId, started.Caller.DisplayName));
        return new StartedCallResult(started.Session.CallId, started.Callee.MembershipId, started.Callee.DisplayName);
    }

    public async Task<JoinCallResult> JoinCall(JoinCallRequest request)
    {
        CallSessionRegistry.Joined joined;
        try { joined = sessions.Join(Context.ConnectionId, request.CallId, request.Generation); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        if (joined.Peer is not null)
        {
            await Clients.Client(joined.Peer.ConnectionId).SendAsync("CallPeerJoined", PeerEvent(joined.Current, joined.Peer));
            await Clients.Caller.SendAsync("CallPeerJoined", PeerEvent(joined.Peer, joined.Current));
        }
        return new JoinCallResult(request.CallId, request.Generation, joined.Current.MembershipId,
            joined.Current.ConnectionId, joined.Current.IsInitiator, joined.Peer is not null,
            joined.Peer?.MembershipId, joined.Peer?.ConnectionId);
    }

    public CallingIceConfiguration GetCallIceConfiguration(Guid callId)
    {
        var identity = RequireIdentity();
        try { sessions.RequireParticipant(Context.ConnectionId, callId); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        return ice.Create(identity.MembershipId);
    }

    public Task CallOffer(CallDescriptionRequest request) => ForwardDescription("CallOffer", request);
    public Task CallAnswer(CallDescriptionRequest request) => ForwardDescription("CallAnswer", request);

    public async Task CallIceCandidate(CallIceCandidateRequest request)
    {
        var (sender, peer) = RequirePeer(request.CallId, request.Generation);
        if (peer is null) return;
        await Clients.Client(peer.ConnectionId).SendAsync("CallIceCandidate",
            new CallIceCandidateEvent(sender.CallId, peer.Generation, sender.MembershipId,
                sender.ConnectionId, peer.ConnectionId, sender.Generation,
                request.Candidate, request.SdpMid, request.SdpMLineIndex));
    }

    public async Task CallMuteState(CallMuteRequest request)
    {
        var (sender, peer) = RequirePeer(request.CallId, request.Generation);
        if (peer is null) return;
        await Clients.Client(peer.ConnectionId).SendAsync("CallMuteState",
            new CallMuteEvent(sender.CallId, peer.Generation, sender.MembershipId,
                sender.ConnectionId, peer.ConnectionId, sender.Generation, request.Muted));
    }

    public async Task EndCall(EndCallRequest request)
    {
        CallSessionRegistry.JoinedParticipant? peer;
        try { (_, peer) = sessions.End(Context.ConnectionId, request.CallId); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        if (peer is not null)
            await Clients.Client(peer.ConnectionId).SendAsync("CallEnded", new CallEndedEvent(request.CallId, "remote"));
    }

    private async Task ForwardDescription(string eventName, CallDescriptionRequest request)
    {
        var (sender, peer) = RequirePeer(request.CallId, request.Generation);
        if (peer is null) return;
        await Clients.Client(peer.ConnectionId).SendAsync(eventName,
            new CallDescriptionEvent(sender.CallId, peer.Generation, sender.MembershipId,
                sender.ConnectionId, peer.ConnectionId, sender.Generation, request.SessionDescription));
    }

    private (CallSessionRegistry.JoinedParticipant Sender, CallSessionRegistry.JoinedParticipant? Peer) RequirePeer(
        Guid callId, long generation)
    {
        try
        {
            var sender = sessions.RequireCurrent(Context.ConnectionId, callId, generation);
            return (sender, sessions.PeerOf(sender));
        }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
    }

    private ApplicationIdentityDescriptor RequireIdentity()
    {
        var principal = Context.User;
        var applicationKey = principal?.FindFirstValue(ApplicationIdentityDefaults.ApplicationKeyClaim);
        if (principal is null ||
            !Guid.TryParse(principal.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim), out var membershipId) ||
            string.IsNullOrWhiteSpace(applicationKey))
            throw new HubException("Authenticated application membership is unavailable.");
        return new ApplicationIdentityDescriptor(
            membershipId,
            Guid.TryParse(principal.FindFirstValue(ClaimTypes.Sid), out var userId) ? userId : null,
            principal.FindFirstValue(ClaimTypes.NameIdentifier) ?? string.Empty,
            applicationKey,
            principal.Identity?.Name ?? string.Empty,
            string.Equals(principal.FindFirstValue(ApplicationIdentityDefaults.GuestClaim), "true", StringComparison.OrdinalIgnoreCase));
    }

    private static CallPeerEvent PeerEvent(
        CallSessionRegistry.JoinedParticipant subject,
        CallSessionRegistry.JoinedParticipant receiver) =>
        new(subject.CallId, receiver.Generation, subject.MembershipId, subject.ConnectionId,
            receiver.ConnectionId, subject.Generation, subject.IsInitiator);
}
