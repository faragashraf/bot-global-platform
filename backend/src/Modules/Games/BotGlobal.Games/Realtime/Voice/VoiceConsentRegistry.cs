namespace BotGlobal.Games.Realtime.Voice;

public sealed class VoiceConsentOptions
{
    public const string SectionName = "Games:Voice:Consent";
    public int RequestLifetimeSeconds { get; set; } = 30;
}

public sealed class VoiceConsentRegistry
{
    private readonly object _gate = new();
    private readonly Dictionary<Guid, Request> _byId = new();
    private readonly Dictionary<(Guid SessionId, int MatchNumber), Guid> _activeByMatch = new();

    public (Request Request, bool Created) RequestVoice(
        Guid sessionId,
        int matchNumber,
        Guid requesterMembershipId,
        Guid recipientMembershipId,
        DateTimeOffset now,
        TimeSpan lifetime)
    {
        if (requesterMembershipId == recipientMembershipId)
            throw new InvalidOperationException("A voice request cannot target its sender.");
        lock (_gate)
        {
            var match = (sessionId, matchNumber);
            if (_activeByMatch.TryGetValue(match, out var activeId) && _byId.TryGetValue(activeId, out var active))
            {
                if (active.Status == Status.Accepted &&
                    (active.RequesterMembershipId == requesterMembershipId || active.RecipientMembershipId == requesterMembershipId))
                    return (active, false);
                if (active.Status == Status.Pending && active.ExpiresAtUtc > now)
                {
                    if (active.RequesterMembershipId == requesterMembershipId && active.RecipientMembershipId == recipientMembershipId)
                        return (active, false);
                    throw new InvalidOperationException("A conflicting voice request is already pending.");
                }
                Complete(active, active.Status == Status.Pending ? Status.TimedOut : active.Status);
            }
            var request = new Request(Guid.NewGuid(), sessionId, matchNumber, requesterMembershipId,
                recipientMembershipId, now, now.Add(lifetime), Status.Pending);
            _byId[request.RequestId] = request;
            _activeByMatch[match] = request.RequestId;
            return (request, true);
        }
    }

    public Request Accept(Guid requestId, Guid sessionId, int matchNumber, Guid recipientMembershipId, DateTimeOffset now) =>
        Transition(requestId, sessionId, matchNumber, recipientMembershipId, now, Status.Accepted, requireRecipient: true);

    public Request Decline(Guid requestId, Guid sessionId, int matchNumber, Guid recipientMembershipId, DateTimeOffset now) =>
        Transition(requestId, sessionId, matchNumber, recipientMembershipId, now, Status.Declined, requireRecipient: true);

    public Request Cancel(Guid requestId, Guid sessionId, int matchNumber, Guid requesterMembershipId, DateTimeOffset now)
    {
        lock (_gate)
        {
            var request = RequireRequest(requestId, sessionId, matchNumber);
            if (requesterMembershipId != request.RequesterMembershipId)
                throw new InvalidOperationException("The participant cannot perform this voice request action.");
            return request.Status switch
            {
                Status.Pending when request.ExpiresAtUtc <= now => Complete(request, Status.TimedOut),
                Status.Pending => Complete(request, Status.Cancelled),
                Status.Accepted => Complete(request, Status.Ended),
                _ => request,
            };
        }
    }

    public Request? Expire(Guid requestId, DateTimeOffset now)
    {
        lock (_gate)
        {
            if (!_byId.TryGetValue(requestId, out var request) || request.Status != Status.Pending || request.ExpiresAtUtc > now)
                return null;
            return Complete(request, Status.TimedOut);
        }
    }

    public Request RequireAccepted(Guid sessionId, int matchNumber, Guid membershipId)
    {
        lock (_gate)
        {
            var match = (sessionId, matchNumber);
            if (!_activeByMatch.TryGetValue(match, out var id) || !_byId.TryGetValue(id, out var request) ||
                request.Status != Status.Accepted ||
                membershipId != request.RequesterMembershipId && membershipId != request.RecipientMembershipId)
                throw new InvalidOperationException("Voice consent is not accepted for this match.");
            return request;
        }
    }

    public Request? Current(Guid sessionId, int matchNumber, Guid membershipId, DateTimeOffset now)
    {
        lock (_gate)
        {
            var match = (sessionId, matchNumber);
            if (!_activeByMatch.TryGetValue(match, out var id) || !_byId.TryGetValue(id, out var request))
                return null;
            if (membershipId != request.RequesterMembershipId && membershipId != request.RecipientMembershipId)
                throw new InvalidOperationException("The participant cannot inspect this voice request.");
            if (request.Status == Status.Pending && request.ExpiresAtUtc <= now)
                return Complete(request, Status.TimedOut);
            return request.Status is Status.Pending or Status.Accepted ? request : null;
        }
    }

    public IReadOnlyList<Request> Disconnect(Guid sessionId, Guid membershipId)
    {
        lock (_gate)
        {
            var active = _activeByMatch
                .Where(x => x.Key.SessionId == sessionId)
                .Select(x => _byId.TryGetValue(x.Value, out var request) ? request : null)
                .Where(x => x is not null &&
                    (x.RequesterMembershipId == membershipId || x.RecipientMembershipId == membershipId))
                .Cast<Request>()
                .ToArray();
            return active.Select(request => request.Status switch
                {
                    Status.Pending => Complete(request, Status.Cancelled),
                    Status.Accepted => Complete(request, Status.Ended),
                    _ => request,
                })
                .ToArray();
        }
    }

    public Request End(Guid requestId, Guid sessionId, int matchNumber, Guid membershipId)
    {
        lock (_gate)
        {
            if (!_byId.TryGetValue(requestId, out var request) || request.SessionId != sessionId || request.MatchNumber != matchNumber)
                throw new InvalidOperationException("The voice consent belongs to another request or match.");
            if (request.Status == Status.Ended) return request;
            if (request.Status != Status.Accepted ||
                membershipId != request.RequesterMembershipId && membershipId != request.RecipientMembershipId)
                throw new InvalidOperationException("Only a participant in an accepted voice session can end it.");
            return Complete(request, Status.Ended);
        }
    }

    private Request Transition(Guid requestId, Guid sessionId, int matchNumber, Guid actorMembershipId,
        DateTimeOffset now, Status destination, bool requireRecipient)
    {
        lock (_gate)
        {
            var request = RequireRequest(requestId, sessionId, matchNumber);
            var requiredActor = requireRecipient ? request.RecipientMembershipId : request.RequesterMembershipId;
            if (actorMembershipId != requiredActor) throw new InvalidOperationException("The participant cannot perform this voice request action.");
            if (request.Status == destination) return request;
            if (request.Status != Status.Pending || request.ExpiresAtUtc <= now)
                throw new InvalidOperationException("The voice request is no longer pending.");
            return Complete(request, destination);
        }
    }

    private Request Complete(Request request, Status status)
    {
        var completed = request with { Status = status };
        _byId[request.RequestId] = completed;
        var match = (request.SessionId, request.MatchNumber);
        if (status != Status.Accepted &&
            _activeByMatch.TryGetValue(match, out var activeId) && activeId == request.RequestId)
            _activeByMatch.Remove(match);
        return completed;
    }

    private Request RequireRequest(Guid requestId, Guid sessionId, int matchNumber)
    {
        if (!_byId.TryGetValue(requestId, out var request) || request.SessionId != sessionId || request.MatchNumber != matchNumber)
            throw new InvalidOperationException("The voice request is stale or belongs to another match.");
        return request;
    }

    public enum Status { Pending, Accepted, Declined, Cancelled, TimedOut, Ended }
    public sealed record Request(Guid RequestId, Guid SessionId, int MatchNumber,
        Guid RequesterMembershipId, Guid RecipientMembershipId, DateTimeOffset CreatedAtUtc,
        DateTimeOffset ExpiresAtUtc, Status Status);
}
