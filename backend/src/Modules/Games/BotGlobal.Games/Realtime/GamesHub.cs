using System.Security.Claims;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application.Sessions;
using BotGlobal.Games.Realtime.Voice;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.Games.Realtime;

[Authorize(
    AuthenticationSchemes = ApplicationIdentityDefaults.Scheme,
    Policy = "application:family-games")]
public sealed class GamesHub(
    IGameSessionService sessions,
    GameConnectionRegistry connections,
    VoiceConnectionRegistry voiceConnections,
    VoiceConsentRegistry voiceConsents,
    IVoiceIceConfigurationProvider iceConfiguration,
    IOptions<VoiceConsentOptions> voiceConsentOptions,
    TimeProvider timeProvider,
    IHubContext<GamesHub> hubContext,
    ILogger<GamesHub> logger) : Hub
{
    public override Task OnConnectedAsync()
    {
        connections.Connected(Context.ConnectionId, RequireIdentity().MembershipId);
        return base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        var voiceParticipant = voiceConnections.Leave(Context.ConnectionId);
        if (voiceParticipant is not null) await NotifyVoicePeerLeft(voiceParticipant, CancellationToken.None);
        var disconnected = connections.Disconnected(Context.ConnectionId);
        if (disconnected.HasValue)
        {
            foreach (var sessionId in disconnected.Value.SessionIds)
            {
                foreach (var consent in voiceConsents.Disconnect(sessionId, disconnected.Value.MembershipId))
                {
                    await NotifyConsentAfterDisconnect(consent, disconnected.Value.MembershipId,
                        Context.ConnectionId, CancellationToken.None);
                }
                await sessions.SetDisconnectedAsync(
                    disconnected.Value.MembershipId,
                    sessionId,
                    CancellationToken.None);
            }
        }

        if (exception is not null)
        {
            logger.LogWarning(exception, "Game realtime connection {ConnectionId} disconnected with an error", Context.ConnectionId);
        }

        await base.OnDisconnectedAsync(exception);
    }

    public async Task<GameSessionSnapshot> Rejoin(Guid sessionId)
    {
        var identity = RequireIdentity();
        // Register the new transport before the authoritative presence update.
        // If an older connection closes concurrently, the registry therefore
        // knows that this participant still has a live session connection.
        connections.Joined(Context.ConnectionId, sessionId);
        var markedConnected = false;
        try
        {
            var result = await sessions.RejoinAsync(identity, sessionId, Context.ConnectionAborted);
            var snapshot = RequireSuccess(result);
            markedConnected = true;
            await Groups.AddToGroupAsync(Context.ConnectionId, GroupName(sessionId), Context.ConnectionAborted);
            await Clients.Caller.SendAsync("GameStateUpdated", snapshot, Context.ConnectionAborted);
            return snapshot;
        }
        catch
        {
            var wasLastConnection = connections.Unjoined(Context.ConnectionId, sessionId);
            if (markedConnected && wasLastConnection)
            {
                await sessions.SetDisconnectedAsync(identity.MembershipId, sessionId, CancellationToken.None);
            }

            throw;
        }
    }

    public async Task<GameSessionSnapshot> Ready(Guid sessionId) =>
        RequireSuccess(await sessions.ReadyAsync(RequireIdentity(), sessionId, Context.ConnectionAborted));

    public async Task<GameSessionSnapshot> Move(XoMoveRequest request) =>
        RequireSuccess(await sessions.MoveAsync(RequireIdentity(), request, Context.ConnectionAborted));

    public async Task<GameSessionSnapshot> RequestRematch(Guid sessionId) =>
        RequireSuccess(await sessions.RequestRematchAsync(RequireIdentity(), sessionId, Context.ConnectionAborted));

    public async Task<GameSessionSnapshot> AcceptRematch(Guid sessionId) =>
        RequireSuccess(await sessions.AcceptRematchAsync(RequireIdentity(), sessionId, Context.ConnectionAborted));

    public async Task<VoiceConsentResult> RequestVoice(VoiceConsentRequest request)
    {
        var (identity, snapshot) = await RequireVoiceParticipant(request.SessionId);
        RequireCurrentMatch(snapshot, request.MatchNumber);
        if (snapshot.Status != "started") throw new HubException("voice_request_session_inactive");
        var recipient = snapshot.Players.Single(x => x.MembershipId != identity.MembershipId);
        var recipientConnection = connections.ResolveOpponentConnection(Context.ConnectionId, request.SessionId,
            identity.MembershipId, recipient.MembershipId)
            ?? throw new HubException("voice_request_peer_unavailable");
        VoiceConsentRegistry.Request created;
        bool wasCreated;
        try
        {
            (created, wasCreated) = voiceConsents.RequestVoice(request.SessionId, request.MatchNumber,
                identity.MembershipId, recipient.MembershipId, timeProvider.GetUtcNow(),
                TimeSpan.FromSeconds(voiceConsentOptions.Value.RequestLifetimeSeconds));
        }
        catch (InvalidOperationException error) { throw new HubException($"voice_request_conflict:{error.Message}"); }

        var voiceEvent = ConsentEvent(created, Context.ConnectionId, recipientConnection, "requesting");
        if (wasCreated)
        {
            await Clients.Client(recipientConnection).SendAsync("VoiceRequested", voiceEvent, Context.ConnectionAborted);
            _ = ExpireVoiceRequest(created.RequestId, created.ExpiresAtUtc);
        }
        LogConsentRoute("request", voiceEvent);
        return new VoiceConsentResult(created.SessionId, created.MatchNumber, created.RequestId,
            created.RequesterMembershipId, created.RecipientMembershipId, created.ExpiresAtUtc, wasCreated);
    }

    public Task AcceptVoice(VoiceConsentAction action) => CompleteVoiceRequest(action, accepted: true);
    public Task DeclineVoice(VoiceConsentAction action) => CompleteVoiceRequest(action, accepted: false);

    public async Task<VoiceConsentStateResult> GetVoiceConsentState(Guid sessionId, int matchNumber)
    {
        var (identity, snapshot) = await RequireVoiceParticipant(sessionId);
        RequireCurrentMatch(snapshot, matchNumber);
        VoiceConsentRegistry.Request? current;
        try { current = voiceConsents.Current(sessionId, matchNumber, identity.MembershipId, timeProvider.GetUtcNow()); }
        catch (InvalidOperationException error) { throw new HubException($"voice_consent_state_invalid:{error.Message}"); }
        if (current?.Status == VoiceConsentRegistry.Status.TimedOut)
        {
            await SendConsentEvent("VoiceRequestTimedOut", current, "timed_out", Context.ConnectionAborted);
            current = null;
        }
        if (current is null)
            return new VoiceConsentStateResult(false, sessionId, matchNumber, Guid.Empty,
                Guid.Empty, Guid.Empty, default, "idle");
        var state = current.Status == VoiceConsentRegistry.Status.Accepted
            ? "accepted"
            : current.RequesterMembershipId == identity.MembershipId ? "requesting" : "incoming_request";
        return new VoiceConsentStateResult(true, current.SessionId, current.MatchNumber, current.RequestId,
            current.RequesterMembershipId, current.RecipientMembershipId, current.ExpiresAtUtc, state);
    }

    public async Task EndVoice(VoiceConsentAction action)
    {
        var (identity, snapshot) = await RequireVoiceParticipant(action.SessionId);
        RequireCurrentMatch(snapshot, action.MatchNumber);
        VoiceConsentRegistry.Request request;
        try { request = voiceConsents.End(action.RequestId, action.SessionId, action.MatchNumber, identity.MembershipId); }
        catch (InvalidOperationException error) { throw new HubException($"voice_consent_stale:{error.Message}"); }
        await SendConsentEvent("VoiceEnded", request, "ended", Context.ConnectionAborted);
    }

    public async Task CancelVoiceRequest(VoiceConsentAction action)
    {
        var (identity, snapshot) = await RequireVoiceParticipant(action.SessionId);
        RequireCurrentMatch(snapshot, action.MatchNumber);
        VoiceConsentRegistry.Request request;
        try { request = voiceConsents.Cancel(action.RequestId, action.SessionId, action.MatchNumber, identity.MembershipId, timeProvider.GetUtcNow()); }
        catch (InvalidOperationException error) { throw new HubException($"voice_request_stale:{error.Message}"); }
        var (eventName, state) = ConsentCompletion(request.Status);
        await SendConsentEvent(eventName, request, state, Context.ConnectionAborted);
    }

    public async Task VoiceUnavailable(VoiceUnavailableRequest unavailable)
    {
        var (identity, snapshot) = await RequireVoiceParticipant(unavailable.SessionId);
        RequireCurrentMatch(snapshot, unavailable.MatchNumber);
        VoiceConsentRegistry.Request request;
        try { request = voiceConsents.RequireAccepted(unavailable.SessionId, unavailable.MatchNumber, identity.MembershipId); }
        catch (InvalidOperationException error) { throw new HubException($"voice_consent_required:{error.Message}"); }
        var remoteMembership = request.RequesterMembershipId == identity.MembershipId
            ? request.RecipientMembershipId : request.RequesterMembershipId;
        var remoteConnection = connections.ResolveOpponentConnection(Context.ConnectionId, unavailable.SessionId,
            identity.MembershipId, remoteMembership);
        if (remoteConnection is null) return;
        var requesterConnection = request.RequesterMembershipId == identity.MembershipId ? Context.ConnectionId : remoteConnection;
        var recipientConnection = request.RecipientMembershipId == identity.MembershipId ? Context.ConnectionId : remoteConnection;
        await Clients.Client(remoteConnection).SendAsync("VoiceUnavailable",
            ConsentEvent(request, requesterConnection, recipientConnection, "unavailable", unavailable.Reason),
            Context.ConnectionAborted);
        // A failed post-consent join must not leave an accepted consent gate
        // behind; a later retry requires a new explicit request.
        voiceConsents.End(request.RequestId, request.SessionId, request.MatchNumber, identity.MembershipId);
    }

    public async Task<VoiceJoinResult> JoinVoiceRoom(VoiceJoinRequest request)
    {
        var (identity, snapshot) = await RequireVoiceParticipant(request.SessionId);
        if (request.Generation <= 0) throw new HubException("voice_generation_invalid");
        try { voiceConsents.RequireAccepted(request.SessionId, snapshot.MatchNumber, identity.MembershipId); }
        catch (InvalidOperationException error) { throw new HubException($"voice_consent_required:{error.Message}"); }
        var local = snapshot.Players.Single(x => x.MembershipId == identity.MembershipId);
        var joined = voiceConnections.Join(Context.ConnectionId, request.SessionId, identity.MembershipId,
            request.Generation, local.Seat == snapshot.Players.Min(x => x.Seat));
        if (joined.Peer is not null)
        {
            await Clients.Client(joined.Peer.ConnectionId).SendAsync("VoicePeerJoined", PeerEvent(joined.Current, joined.Peer), Context.ConnectionAborted);
            await Clients.Caller.SendAsync("VoicePeerJoined", PeerEvent(joined.Peer, joined.Current), Context.ConnectionAborted);
        }
        logger.LogInformation(
            "Voice topology session {SessionId}: local membership {LocalMembershipId} connection {LocalConnectionId} -> peer membership {PeerMembershipId} connection {PeerConnectionId}; generation {Generation}; initiator {Initiator}",
            request.SessionId, identity.MembershipId, Context.ConnectionId, joined.Peer?.MembershipId,
            joined.Peer?.ConnectionId, request.Generation, joined.Current.IsInitiator);
        return new VoiceJoinResult(request.SessionId, request.Generation, identity.MembershipId, Context.ConnectionId,
            joined.Current.IsInitiator, joined.Peer is not null, joined.Peer?.MembershipId, joined.Peer?.ConnectionId);
    }

    public async Task LeaveVoiceRoom(Guid sessionId, long generation)
    {
        var identity = RequireIdentity();
        RequireCurrentVoice(identity, sessionId, generation);
        var departed = voiceConnections.Leave(Context.ConnectionId);
        if (departed is not null) await NotifyVoicePeerLeft(departed, Context.ConnectionAborted);
    }

    public Task VoiceOffer(VoiceDescriptionRequest request) => ForwardDescription("VoiceOffer", request);
    public Task VoiceAnswer(VoiceDescriptionRequest request) => ForwardDescription("VoiceAnswer", request);

    public async Task VoiceIceCandidate(VoiceIceCandidateRequest request)
    {
        var sender = RequireCurrentVoice(RequireIdentity(), request.SessionId, request.Generation);
        var peer = voiceConnections.PeerOf(sender);
        if (peer is null) return;
        LogVoiceRoute("ICE candidate", sender, peer);
        await Clients.Client(peer.ConnectionId).SendAsync("VoiceIceCandidate",
            new VoiceIceCandidateEvent(sender.SessionId, peer.Generation, sender.MembershipId,
                sender.ConnectionId, peer.ConnectionId, sender.Generation,
                request.Candidate, request.SdpMid, request.SdpMLineIndex), Context.ConnectionAborted);
    }

    public async Task VoiceMuteState(VoiceMuteRequest request)
    {
        var sender = RequireCurrentVoice(RequireIdentity(), request.SessionId, request.Generation);
        var peer = voiceConnections.PeerOf(sender);
        if (peer is null) return;
        LogVoiceRoute("mute state", sender, peer);
        await Clients.Client(peer.ConnectionId).SendAsync("VoiceMuteState",
            new VoiceMuteEvent(sender.SessionId, peer.Generation, sender.MembershipId,
                sender.ConnectionId, peer.ConnectionId, sender.Generation, request.Muted), Context.ConnectionAborted);
    }

    public async Task<VoiceIceConfiguration> GetVoiceIceConfiguration(Guid sessionId)
    {
        var (identity, _) = await RequireVoiceParticipant(sessionId);
        return iceConfiguration.Create(identity.MembershipId);
    }

    public static string GroupName(Guid sessionId) => $"game:{sessionId:N}";

    private ApplicationIdentityDescriptor RequireIdentity()
    {
        var principal = Context.User;
        if (principal is null ||
            !Guid.TryParse(principal.FindFirstValue(ApplicationIdentityDefaults.MembershipIdClaim), out var membershipId))
        {
            throw new HubException("Authenticated application membership is unavailable.");
        }

        return new ApplicationIdentityDescriptor(
            membershipId,
            Guid.TryParse(principal.FindFirstValue(ClaimTypes.Sid), out var userId) ? userId : null,
            principal.FindFirstValue(ClaimTypes.NameIdentifier) ?? string.Empty,
            principal.FindFirstValue(ApplicationIdentityDefaults.ApplicationKeyClaim) ?? string.Empty,
            principal.Identity?.Name ?? string.Empty,
            string.Equals(
                principal.FindFirstValue(ApplicationIdentityDefaults.GuestClaim),
                "true",
                StringComparison.OrdinalIgnoreCase));
    }

    private async Task<(ApplicationIdentityDescriptor Identity, GameSessionSnapshot Snapshot)> RequireVoiceParticipant(Guid sessionId)
    {
        var identity = RequireIdentity();
        var snapshot = RequireSuccess(await sessions.GetAsync(identity, sessionId, Context.ConnectionAborted));
        if (!snapshot.Ruleset.VoiceEnabled) throw new HubException("voice_disabled:Voice is disabled for this session.");
        if (snapshot.Status is not ("waiting" or "started" or "completed"))
            throw new HubException("voice_session_invalid:The game session is not voice joinable.");
        return (identity, snapshot);
    }

    private static void RequireCurrentMatch(GameSessionSnapshot snapshot, int matchNumber)
    {
        if (snapshot.MatchNumber != matchNumber) throw new HubException("voice_request_match_stale");
    }

    private async Task CompleteVoiceRequest(VoiceConsentAction action, bool accepted)
    {
        var (identity, snapshot) = await RequireVoiceParticipant(action.SessionId);
        RequireCurrentMatch(snapshot, action.MatchNumber);
        VoiceConsentRegistry.Request request;
        try
        {
            request = accepted
                ? voiceConsents.Accept(action.RequestId, action.SessionId, action.MatchNumber, identity.MembershipId, timeProvider.GetUtcNow())
                : voiceConsents.Decline(action.RequestId, action.SessionId, action.MatchNumber, identity.MembershipId, timeProvider.GetUtcNow());
        }
        catch (InvalidOperationException error) { throw new HubException($"voice_request_stale:{error.Message}"); }
        await SendConsentEvent(accepted ? "VoiceAccepted" : "VoiceDeclined", request,
            accepted ? "accepted" : "declined", Context.ConnectionAborted);
    }

    private async Task SendConsentEvent(string eventName, VoiceConsentRegistry.Request request, string state,
        CancellationToken cancellationToken)
    {
        var requesterConnection = connections.ResolveParticipantConnection(request.SessionId, request.RequesterMembershipId);
        var recipientConnection = connections.ResolveParticipantConnection(request.SessionId, request.RecipientMembershipId);
        if (requesterConnection is null || recipientConnection is null) return;
        var voiceEvent = ConsentEvent(request, requesterConnection, recipientConnection, state);
        await hubContext.Clients.Client(requesterConnection).SendAsync(eventName, voiceEvent, cancellationToken);
        if (!string.Equals(requesterConnection, recipientConnection, StringComparison.Ordinal))
            await hubContext.Clients.Client(recipientConnection).SendAsync(eventName, voiceEvent, cancellationToken);
        LogConsentRoute(state, voiceEvent);
    }

    private async Task ExpireVoiceRequest(Guid requestId, DateTimeOffset expiresAtUtc)
    {
        var delay = expiresAtUtc - timeProvider.GetUtcNow();
        if (delay > TimeSpan.Zero) await Task.Delay(delay, timeProvider, CancellationToken.None);
        var expired = voiceConsents.Expire(requestId, timeProvider.GetUtcNow());
        if (expired is not null) await SendConsentEvent("VoiceRequestTimedOut", expired, "timed_out", CancellationToken.None);
    }

    private static VoiceConsentEvent ConsentEvent(VoiceConsentRegistry.Request request,
        string requesterConnection, string recipientConnection, string state, string? reason = null) =>
        new(request.SessionId, request.MatchNumber, request.RequestId, request.RequesterMembershipId,
            requesterConnection, request.RecipientMembershipId, recipientConnection, request.ExpiresAtUtc, state, reason);

    private void LogConsentRoute(string operation, VoiceConsentEvent voiceEvent) =>
        logger.LogInformation(
            "Voice consent {Operation} session {SessionId} match {MatchNumber}: membership {RequesterMembershipId} connection {RequesterConnectionId} -> membership {RecipientMembershipId} connection {RecipientConnectionId}; request {RequestId}",
            operation, voiceEvent.SessionId, voiceEvent.MatchNumber, voiceEvent.RequesterMembershipId,
            voiceEvent.RequesterConnectionId, voiceEvent.RecipientMembershipId,
            voiceEvent.RecipientConnectionId, voiceEvent.RequestId);

    private VoiceConnectionRegistry.Participant RequireCurrentVoice(ApplicationIdentityDescriptor identity, Guid sessionId, long generation)
    {
        try { return voiceConnections.RequireCurrent(Context.ConnectionId, sessionId, identity.MembershipId, generation); }
        catch (InvalidOperationException) { throw new HubException("voice_generation_stale"); }
    }

    private async Task ForwardDescription(string eventName, VoiceDescriptionRequest request)
    {
        var sender = RequireCurrentVoice(RequireIdentity(), request.SessionId, request.Generation);
        var peer = voiceConnections.PeerOf(sender);
        if (peer is null) return;
        LogVoiceRoute(eventName, sender, peer);
        await Clients.Client(peer.ConnectionId).SendAsync(eventName,
            new VoiceDescriptionEvent(sender.SessionId, peer.Generation, sender.MembershipId,
                sender.ConnectionId, peer.ConnectionId, sender.Generation, request.SessionDescription),
            Context.ConnectionAborted);
    }

    private async Task NotifyVoicePeerLeft(VoiceConnectionRegistry.Participant departed, CancellationToken cancellationToken)
    {
        var peer = voiceConnections.PeerOf(departed);
        if (peer is not null)
            await Clients.Client(peer.ConnectionId).SendAsync("VoicePeerLeft", PeerEvent(departed, peer), cancellationToken);
    }

    private async Task NotifyConsentAfterDisconnect(VoiceConsentRegistry.Request request,
        Guid disconnectedMembershipId, string disconnectedConnectionId, CancellationToken cancellationToken)
    {
        var remoteMembershipId = request.RequesterMembershipId == disconnectedMembershipId
            ? request.RecipientMembershipId : request.RequesterMembershipId;
        var remoteConnectionId = connections.ResolveParticipantConnection(request.SessionId, remoteMembershipId);
        if (remoteConnectionId is null) return;
        var requesterConnectionId = request.RequesterMembershipId == disconnectedMembershipId
            ? disconnectedConnectionId : remoteConnectionId;
        var recipientConnectionId = request.RecipientMembershipId == disconnectedMembershipId
            ? disconnectedConnectionId : remoteConnectionId;
        var (eventName, state) = ConsentCompletion(request.Status);
        var voiceEvent = ConsentEvent(request, requesterConnectionId, recipientConnectionId, state,
            "participant_disconnected");
        await Clients.Client(remoteConnectionId).SendAsync(eventName, voiceEvent, cancellationToken);
        LogConsentRoute(state, voiceEvent);
    }

    private static (string EventName, string State) ConsentCompletion(VoiceConsentRegistry.Status status) => status switch
    {
        VoiceConsentRegistry.Status.Cancelled => ("VoiceRequestCancelled", "cancelled"),
        VoiceConsentRegistry.Status.Declined => ("VoiceDeclined", "declined"),
        VoiceConsentRegistry.Status.TimedOut => ("VoiceRequestTimedOut", "timed_out"),
        VoiceConsentRegistry.Status.Ended => ("VoiceEnded", "ended"),
        _ => throw new InvalidOperationException($"Voice consent status {status} is not terminal."),
    };

    private static VoicePeerEvent PeerEvent(VoiceConnectionRegistry.Participant subject, VoiceConnectionRegistry.Participant receiver) =>
        new(subject.SessionId, receiver.Generation, subject.MembershipId, subject.ConnectionId,
            receiver.ConnectionId, subject.Generation, subject.IsInitiator);

    private void LogVoiceRoute(string operation, VoiceConnectionRegistry.Participant sender, VoiceConnectionRegistry.Participant peer) =>
        logger.LogInformation(
            "Voice {Operation} route session {SessionId}: membership {SenderMembershipId} connection {SenderConnectionId} -> membership {ReceiverMembershipId} connection {ReceiverConnectionId}",
            operation, sender.SessionId, sender.MembershipId, sender.ConnectionId, peer.MembershipId, peer.ConnectionId);

    private static GameSessionSnapshot RequireSuccess(GameCommandResult<GameSessionSnapshot> result) =>
        result.Succeeded && result.Value is not null
            ? result.Value
            : throw new HubException($"{result.ErrorCode}:{result.ErrorMessage}");
}
