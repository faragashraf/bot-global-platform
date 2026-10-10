using System.Security.Claims;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Calling;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;
using BotGlobal.Calling.Application;
using BotGlobal.Contracts.Communication;

namespace BotGlobal.Calling.Realtime;

[Authorize(AuthenticationSchemes = ApplicationIdentityDefaults.Scheme + "," + NqrbGuestCallAuthenticationDefaults.Scheme)]
public sealed class CallingHub(
    CallSessionRegistry sessions,
    CallingIceConfigurationProvider ice,
    NqrbGuestCallInviteService guestInvites,
    ICallingParticipantDirectory participants,
    INqrbCallEligibilityService nqrbEligibility,
    IIncomingCallNotificationDispatcher notifications,
    ICallActivityService activity,
    CallingPresenceAdapter callingPresence,
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
        var (identity, credential) = await RequireRegisteredConnectionAsync();
        if (string.Equals(identity.ApplicationKey, BotGlobalApplications.Nqrb, StringComparison.Ordinal))
        {
            var eligibility = await nqrbEligibility.EvaluateAsync(
                identity.MembershipId,
                request.CalleeMembershipId,
                Context.ConnectionAborted);
            if (!eligibility.CanCall)
                throw new HubException("call_peer_unavailable");
            await RevalidateRegisteredConnectionAsync(identity, credential);
        }
        var callee = await participants.FindAsync(identity.ApplicationKey, request.CalleeMembershipId, Context.ConnectionAborted);
        await RevalidateRegisteredConnectionAsync(identity, credential);
        if (callee is null || !callee.IsActive) throw new HubException("call_peer_unavailable");
        if (string.Equals(identity.ApplicationKey, BotGlobalApplications.Nqrb, StringComparison.Ordinal))
        {
            var presence = await callingPresence.EvaluateAsync(
                credential,
                identity,
                callee,
                Context.ConnectionAborted);
            if (presence.AuthoritativelyUnavailable)
                throw new HubException("call_peer_unavailable");
            await RevalidateRegisteredConnectionAsync(identity, credential);
            var recheck = await nqrbEligibility.EvaluateAsync(
                identity.MembershipId,
                request.CalleeMembershipId,
                Context.ConnectionAborted);
            if (!recheck.CanCall) throw new HubException("call_peer_unavailable");
            await RevalidateRegisteredConnectionAsync(identity, credential);
        }
        CallSessionRegistry.Started started;
        try { started = sessions.Start(Context.ConnectionId, callee, timeProvider.GetUtcNow(), TimeSpan.FromSeconds(45)); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        try
        {
            await RevalidateRegisteredConnectionAsync(identity, credential);
            await activity.StartAsync(started.Session, Context.ConnectionAborted);
            await RevalidateRegisteredConnectionAsync(identity, credential);
        }
        catch (HubException)
        {
            sessions.End(Context.ConnectionId, started.Session.CallId);
            throw;
        }
        catch (OperationCanceledException)
        {
            sessions.End(Context.ConnectionId, started.Session.CallId);
            throw;
        }
        catch
        {
            sessions.End(Context.ConnectionId, started.Session.CallId);
            throw new HubException("call_history_unavailable");
        }
        try
        {
            foreach (var connection in started.CalleeConnections)
            {
                await RevalidateRegisteredConnectionAsync(identity, credential);
                await Clients.Client(connection.ConnectionId).SendAsync("CallOffered",
                new CallOfferedEvent(started.Session.CallId, started.Session.ApplicationKey,
                    started.Caller.MembershipId, started.Caller.DisplayName));
            }
            await RevalidateRegisteredConnectionAsync(identity, credential);
            await notifications.DispatchAsync(new IncomingCallNotification(
                started.Session.ApplicationKey, started.Session.CalleeSubjectId, started.Session.CallId,
                IncomingCallNotificationKind.Offered, started.Session.CallerDisplayName, started.Session.ExpiresAtUtc),
                Context.ConnectionAborted);
            await RevalidateRegisteredConnectionAsync(identity, credential);
        }
        catch
        {
            sessions.End(Context.ConnectionId, started.Session.CallId);
            throw;
        }
        return new StartedCallResult(started.Session.CallId, started.Session.CalleeMembershipId,
            started.Session.CalleeDisplayName, started.Session.Delivery());
    }

    public async Task ConfirmIncomingReceipt(ConfirmIncomingReceiptRequest request)
    {
        var (identity, credential) = await RequireRegisteredConnectionAsync();
        await RevalidateRegisteredConnectionAsync(identity, credential);
        CallSessionRegistry.Transition transition;
        try { transition = sessions.ConfirmIncomingReceipt(Context.ConnectionId, request.CallId, timeProvider.GetUtcNow()); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        if (!transition.Changed) return;
        var status = transition.Session.Delivery();
        foreach (var connection in transition.PeerConnections)
            await Clients.Client(connection.ConnectionId).SendAsync("CallDeliveryChanged", status, Context.ConnectionAborted);
    }

    public async Task<CallDeliveryStatus> GetCallDeliveryStatus(IncomingCallLookupRequest request)
    {
        var (identity, credential) = await RequireRegisteredConnectionAsync();
        await RevalidateRegisteredConnectionAsync(identity, credential);
        try { return sessions.DeliveryStatus(Context.ConnectionId, request.CallId, timeProvider.GetUtcNow()); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
    }

    public async Task<IncomingCallResult> GetIncomingCall(IncomingCallLookupRequest request)
    {
        var (identity, credential) = await RequireRegisteredConnectionAsync();
        await RevalidateRegisteredConnectionAsync(identity, credential);
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
        var (identity, credential) = await RequireRegisteredConnectionAsync();
        await RevalidateRegisteredConnectionAsync(identity, credential);
        CallSessionRegistry.Transition transition;
        try { transition = sessions.Answer(Context.ConnectionId, request.CallId, timeProvider.GetUtcNow()); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        await activity.AnswerAsync(transition.Session, timeProvider.GetUtcNow(), Context.ConnectionAborted);
        await RevalidateRegisteredConnectionAsync(identity, credential);
        if (!transition.Changed) return;
        foreach (var connection in transition.PeerConnections)
        {
            await Clients.Client(connection.ConnectionId).SendAsync("CallAnswered", new CallStateEvent(request.CallId, "answered"));
            await Clients.Client(connection.ConnectionId).SendAsync("CallDeliveryChanged", transition.Session.Delivery(), Context.ConnectionAborted);
        }
        foreach (var connection in sessions.ConnectedParticipants(transition.Session.CalleeMembershipId, transition.Session.ApplicationKey)
                     .Where(connection => connection.ConnectionId != Context.ConnectionId))
            await Clients.Client(connection.ConnectionId).SendAsync("CallEnded", new CallEndedEvent(request.CallId, "answered_elsewhere"));
        await NotifyCalleeOfTerminalOfferAsync(transition.Session, IncomingCallNotificationKind.AnsweredElsewhere);
    }

    public async Task RejectIncomingCall(RejectCallRequest request)
    {
        var (identity, credential) = await RequireRegisteredConnectionAsync();
        await RevalidateRegisteredConnectionAsync(identity, credential);
        CallSessionRegistry.Transition transition;
        try { transition = sessions.Reject(Context.ConnectionId, request.CallId, timeProvider.GetUtcNow()); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        await activity.FinishAsync(transition.Session, timeProvider.GetUtcNow(), Context.ConnectionAborted);
        await RevalidateRegisteredConnectionAsync(identity, credential);
        if (transition.Session.IsGuestCall)
            guestInvites.Complete(transition.Session.GuestInviteId);
        if (!transition.Changed) return;
        foreach (var connection in transition.PeerConnections)
            await Clients.Client(connection.ConnectionId).SendAsync("CallRejected", new CallStateEvent(request.CallId, "rejected"));
        foreach (var connection in sessions.ConnectedParticipants(transition.Session.CalleeMembershipId, transition.Session.ApplicationKey)
                     .Where(connection => connection.ConnectionId != Context.ConnectionId))
            await Clients.Client(connection.ConnectionId).SendAsync("CallEnded", new CallEndedEvent(request.CallId, "rejected"));
        await NotifyCalleeOfTerminalOfferAsync(transition.Session, IncomingCallNotificationKind.Cancelled);
    }

    private async Task NotifyCalleeOfTerminalOfferAsync(
        CallSessionRegistry.Session session,
        IncomingCallNotificationKind kind)
    {
        try
        {
            await notifications.DispatchAsync(new IncomingCallNotification(
                session.ApplicationKey, session.CalleeSubjectId, session.CallId,
                kind, session.CallerDisplayName, session.ExpiresAtUtc), Context.ConnectionAborted);
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            logger.LogWarning("Terminal call notification failed. Kind={Kind}, ErrorType={ErrorType}",
                kind, error.GetType().Name);
        }
    }

    public async Task<JoinCallResult> JoinCall(JoinCallRequest request)
    {
        await RequireCurrentParticipantConnectionAsync(request.CallId);
        CallSessionRegistry.Joined joined;
        try { joined = sessions.Join(Context.ConnectionId, request.CallId, request.Generation); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        await activity.JoinedAsync(joined.Session, joined.Current.MembershipId, timeProvider.GetUtcNow(), Context.ConnectionAborted);
        await RequireCurrentParticipantConnectionAsync(request.CallId);
        if (joined.Peer is not null)
        {
            await Clients.Client(joined.Peer.ConnectionId).SendAsync("CallPeerJoined", PeerEvent(joined.Current, joined.Peer));
            await Clients.Caller.SendAsync("CallPeerJoined", PeerEvent(joined.Peer, joined.Current));
        }
        return new JoinCallResult(request.CallId, request.Generation, joined.Current.MembershipId,
            joined.Current.ConnectionId, joined.Current.IsInitiator, joined.Peer is not null,
            joined.Peer?.MembershipId, joined.Peer?.ConnectionId);
    }

    public async Task<CallingIceConfiguration> GetCallIceConfiguration(Guid callId)
    {
        var identity = RequireIdentity();
        await RequireCurrentParticipantConnectionAsync(callId);
        RequireIceCredentialParticipant(callId);
        try
        {
            var session = sessions.RequireSessionForParticipant(Context.ConnectionId, callId);
            var configuration = session.IsGuestCall
                ? ice.CreateStunOnly(identity.MembershipId)
                : await ice.CreateAsync(identity.MembershipId, Context.ConnectionAborted);
            await RequireCurrentParticipantConnectionAsync(callId);
            RequireIceCredentialParticipant(callId);
            return configuration;
        }
        catch (CallingIceConfigurationUnavailableException error)
        {
            logger.LogWarning("Temporary calling ICE credentials are unavailable. ErrorType={ErrorType}", error.GetType().Name);
            throw new HubException("calling_ice_unavailable");
        }
    }

    public Task CallOffer(CallDescriptionRequest request) => ForwardDescription("CallOffer", request);
    public Task CallAnswer(CallDescriptionRequest request) => ForwardDescription("CallAnswer", request);

    public async Task CallIceCandidate(CallIceCandidateRequest request)
    {
        await RequireCurrentParticipantConnectionAsync(request.CallId);
        var (sender, peer) = RequirePeer(request.CallId, request.Generation);
        if (peer is null) return;
        await Clients.Client(peer.ConnectionId).SendAsync("CallIceCandidate",
            new CallIceCandidateEvent(sender.CallId, peer.Generation, sender.MembershipId,
                sender.ConnectionId, peer.ConnectionId, sender.Generation,
                request.Candidate, request.SdpMid, request.SdpMLineIndex));
    }

    public async Task CallMuteState(CallMuteRequest request)
    {
        await RequireCurrentParticipantConnectionAsync(request.CallId);
        var (sender, peer) = RequirePeer(request.CallId, request.Generation);
        if (peer is null) return;
        await Clients.Client(peer.ConnectionId).SendAsync("CallMuteState",
            new CallMuteEvent(sender.CallId, peer.Generation, sender.MembershipId,
                sender.ConnectionId, peer.ConnectionId, sender.Generation, request.Muted));
    }

    public async Task EndCall(EndCallRequest request)
    {
        await RequireCurrentParticipantConnectionAsync(request.CallId);
        CallSessionRegistry.Transition transition;
        try { transition = sessions.End(Context.ConnectionId, request.CallId, request.Reason); }
        catch (InvalidOperationException error) { throw new HubException(error.Message); }
        var endingIdentity = RequireIdentity();
        logger.LogInformation("Call ended by authenticated participant. Role={Role}, Outcome={Outcome}",
            endingIdentity.MembershipId == transition.Session.CallerMembershipId ? "caller" : "callee", transition.Session.Status);
        await activity.FinishAsync(transition.Session, timeProvider.GetUtcNow(), Context.ConnectionAborted);
        if (transition.Session.IsGuestCall)
            guestInvites.Complete(transition.Session.GuestInviteId);
        foreach (var peer in transition.PeerConnections)
            await Clients.Client(peer.ConnectionId).SendAsync("CallEnded", new CallEndedEvent(request.CallId, transition.Session.Status.ToString().ToLowerInvariant()));
        if (transition.Changed && transition.Session.Status == CallSessionRegistry.CallStatus.Cancelled)
            await notifications.DispatchAsync(new IncomingCallNotification(transition.Session.ApplicationKey,
                transition.Session.CalleeSubjectId, request.CallId, IncomingCallNotificationKind.Cancelled,
                transition.Session.CallerDisplayName, transition.Session.ExpiresAtUtc), Context.ConnectionAborted);
    }

    private async Task ForwardDescription(string eventName, CallDescriptionRequest request)
    {
        await RequireCurrentParticipantConnectionAsync(request.CallId);
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

    private void RequireIceCredentialParticipant(Guid callId)
    {
        try { sessions.RequireLiveIceParticipant(Context.ConnectionId, callId, timeProvider.GetUtcNow()); }
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

    private ApplicationIdentityDescriptor RequireRegisteredIdentity()
    {
        var identity = RequireIdentity();
        if (IsScopedGuestCallIdentity()) throw new HubException("guest_call_scope_required");
        return identity;
    }

    private async Task<(ApplicationIdentityDescriptor Identity, PresenceConnectionCredential Credential)> RequireRegisteredConnectionAsync()
    {
        var identity = RequireRegisteredIdentity();
        Context.Items.TryGetValue(PresenceConnectionCredential.HttpContextItemKey, out var hubValue);
        var credential = hubValue as PresenceConnectionCredential;
        if (credential is null && Context.GetHttpContext() is { } httpContext &&
            httpContext.Items.TryGetValue(PresenceConnectionCredential.HttpContextItemKey, out var httpValue))
            credential = httpValue as PresenceConnectionCredential;
        if (credential is null) throw new HubException("call_connection_credential_required");
        var current = await callingPresence.ValidateConnectionAsync(credential, Context.ConnectionAborted);
        if (current is null || current.IsGuest || current.Authority.MembershipId != identity.MembershipId ||
            !string.Equals(current.Authority.ApplicationKey, identity.ApplicationKey, StringComparison.Ordinal))
            throw new HubException("call_connection_credential_stale");
        return (identity, credential);
    }

    private async Task RevalidateRegisteredConnectionAsync(
        ApplicationIdentityDescriptor expected,
        PresenceConnectionCredential credential)
    {
        var current = await callingPresence.ValidateConnectionAsync(credential, Context.ConnectionAborted);
        if (current is null || current.IsGuest || current.Authority.SessionId != credential.SessionId ||
            current.Authority.MembershipId != expected.MembershipId ||
            current.Authority.MembershipId != credential.MembershipId ||
            !string.Equals(current.Authority.ApplicationKey, expected.ApplicationKey, StringComparison.Ordinal) ||
            !string.Equals(current.Authority.ApplicationKey, credential.ApplicationKey, StringComparison.Ordinal) ||
            !string.Equals(current.Authority.CredentialRevision, credential.CredentialRevision, StringComparison.Ordinal))
            throw new HubException("call_connection_credential_stale");
    }

    private void RequireGuestBindingIfScoped(Guid callId)
    {
        if (!IsScopedGuestCallIdentity()) return;
        var principal = Context.User;
        if (!Guid.TryParse(principal?.FindFirstValue(NqrbGuestCallAuthenticationDefaults.InviteIdClaim), out var inviteId) ||
            !Guid.TryParse(principal.FindFirstValue(NqrbGuestCallAuthenticationDefaults.CallIdClaim), out var boundCallId) ||
            !Guid.TryParse(principal.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim), out var membershipId) ||
            boundCallId != callId ||
            !guestInvites.IsGuestAuthorized(inviteId, membershipId, callId))
            throw new HubException("guest_call_scope_required");
    }

    private async Task RequireCurrentParticipantConnectionAsync(Guid callId)
    {
        if (IsScopedGuestCallIdentity())
        {
            RequireGuestBindingIfScoped(callId);
            return;
        }
        var (identity, credential) = await RequireRegisteredConnectionAsync();
        await RevalidateRegisteredConnectionAsync(identity, credential);
    }

    private bool IsScopedGuestCallIdentity() =>
        string.Equals(Context.User?.Identity?.AuthenticationType, NqrbGuestCallAuthenticationDefaults.Scheme, StringComparison.Ordinal) ||
        Context.User?.HasClaim(claim => claim.Type == NqrbGuestCallAuthenticationDefaults.InviteIdClaim) == true;

    private static CallPeerEvent PeerEvent(
        CallSessionRegistry.JoinedParticipant subject,
        CallSessionRegistry.JoinedParticipant receiver) =>
        new(subject.CallId, receiver.Generation, subject.MembershipId, subject.ConnectionId,
            receiver.ConnectionId, subject.Generation, subject.IsInitiator);
}
