using System.Security.Claims;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Calling;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;
using BotGlobal.Calling.Application;

namespace BotGlobal.Calling.Realtime;

[Authorize(AuthenticationSchemes = ApplicationIdentityDefaults.Scheme)]
public sealed class CallingHub(
    CallSessionRegistry sessions,
    CallingIceConfigurationProvider ice,
    ICallingParticipantDirectory participants,
    IIncomingCallNotificationDispatcher notifications,
    ICallActivityService activity,
    TimeProvider timeProvider,
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
        var identity = RequireIdentity();
        var callee = await participants.FindAsync(identity.ApplicationKey, request.CalleeMembershipId, Context.ConnectionAborted);
        if (callee is null || !callee.IsActive) throw new HubException("call_peer_unavailable");
        CallSessionRegistry.Started started;
        try { started = sessions.Start(Context.ConnectionId, callee, timeProvider.GetUtcNow(), TimeSpan.FromSeconds(45)); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        try { await activity.StartAsync(started.Session, Context.ConnectionAborted); }
        catch
        {
            sessions.End(Context.ConnectionId, started.Session.CallId);
            throw new HubException("call_history_unavailable");
        }
        foreach (var connection in started.CalleeConnections)
            await Clients.Client(connection.ConnectionId).SendAsync("CallOffered",
            new CallOfferedEvent(started.Session.CallId, started.Session.ApplicationKey,
                started.Caller.MembershipId, started.Caller.DisplayName));
        await notifications.DispatchAsync(new IncomingCallNotification(
            started.Session.ApplicationKey, started.Session.CalleeSubjectId, started.Session.CallId,
            IncomingCallNotificationKind.Offered, started.Session.CallerDisplayName, started.Session.ExpiresAtUtc),
            Context.ConnectionAborted);
        return new StartedCallResult(started.Session.CallId, started.Session.CalleeMembershipId, started.Session.CalleeDisplayName);
    }

    public IncomingCallResult GetIncomingCall(IncomingCallLookupRequest request)
    {
        try
        {
            var incoming = sessions.RequireIncoming(Context.ConnectionId, request.CallId, timeProvider.GetUtcNow());
            return new IncomingCallResult(incoming.Session.CallId, incoming.Session.ApplicationKey,
                incoming.Session.CallerMembershipId, incoming.Session.CallerDisplayName, incoming.Session.ExpiresAtUtc);
        }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
    }

    public async Task AnswerIncomingCall(AnswerCallRequest request)
    {
        CallSessionRegistry.Transition transition;
        try { transition = sessions.Answer(Context.ConnectionId, request.CallId, timeProvider.GetUtcNow()); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        await activity.AnswerAsync(transition.Session, timeProvider.GetUtcNow(), Context.ConnectionAborted);
        if (!transition.Changed) return;
        foreach (var connection in transition.PeerConnections)
            await Clients.Client(connection.ConnectionId).SendAsync("CallAnswered", new CallStateEvent(request.CallId, "answered"));
        foreach (var connection in sessions.ConnectedParticipants(transition.Session.CalleeMembershipId, transition.Session.ApplicationKey)
                     .Where(connection => connection.ConnectionId != Context.ConnectionId))
            await Clients.Client(connection.ConnectionId).SendAsync("CallEnded", new CallEndedEvent(request.CallId, "answered_elsewhere"));
    }

    public async Task RejectIncomingCall(RejectCallRequest request)
    {
        CallSessionRegistry.Transition transition;
        try { transition = sessions.Reject(Context.ConnectionId, request.CallId, timeProvider.GetUtcNow()); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        await activity.FinishAsync(transition.Session, timeProvider.GetUtcNow(), Context.ConnectionAborted);
        if (!transition.Changed) return;
        foreach (var connection in transition.PeerConnections)
            await Clients.Client(connection.ConnectionId).SendAsync("CallRejected", new CallStateEvent(request.CallId, "rejected"));
    }

    public async Task<JoinCallResult> JoinCall(JoinCallRequest request)
    {
        CallSessionRegistry.Joined joined;
        try { joined = sessions.Join(Context.ConnectionId, request.CallId, request.Generation); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        await activity.JoinedAsync(joined.Session, joined.Current.MembershipId, timeProvider.GetUtcNow(), Context.ConnectionAborted);
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
        CallSessionRegistry.Transition transition;
        try { transition = sessions.End(Context.ConnectionId, request.CallId, request.Reason); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        var endingIdentity = RequireIdentity();
        logger.LogInformation("Call ended by authenticated participant. Role={Role}, Outcome={Outcome}",
            endingIdentity.MembershipId == transition.Session.CallerMembershipId ? "caller" : "callee", transition.Session.Status);
        await activity.FinishAsync(transition.Session, timeProvider.GetUtcNow(), Context.ConnectionAborted);
        foreach (var peer in transition.PeerConnections)
            await Clients.Client(peer.ConnectionId).SendAsync("CallEnded", new CallEndedEvent(request.CallId, transition.Session.Status.ToString().ToLowerInvariant()));
        if (transition.Changed && transition.Session.Status == CallSessionRegistry.CallStatus.Cancelled)
            await notifications.DispatchAsync(new IncomingCallNotification(transition.Session.ApplicationKey,
                transition.Session.CalleeSubjectId, request.CallId, IncomingCallNotificationKind.Cancelled,
                transition.Session.CallerDisplayName, transition.Session.ExpiresAtUtc), Context.ConnectionAborted);
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
