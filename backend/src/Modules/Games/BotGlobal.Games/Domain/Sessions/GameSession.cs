namespace BotGlobal.Games.Domain.Sessions;

public enum GameSessionStatus
{
    Waiting = 0,
    Started = 1,
    Completed = 2
}

public sealed class GameSession
{
    private readonly List<GamePlayer> _players = [];

    private GameSession()
    {
    }

    public GameSession(
        Guid id,
        string applicationKey,
        string joinCode,
        string gameType,
        string rulesetKey,
        int maximumPlayers,
        Guid createdByMembershipId,
        DateTimeOffset createdAtUtc,
        string? requiredEntitlement = null)
    {
        Id = id;
        ApplicationKey = Require(applicationKey, nameof(applicationKey), 80);
        JoinCode = Require(joinCode, nameof(joinCode), 12).ToUpperInvariant();
        GameType = Require(gameType, nameof(gameType), 40);
        RulesetKey = Require(rulesetKey, nameof(rulesetKey), 80);
        MaximumPlayers = maximumPlayers > 1
            ? maximumPlayers
            : throw new ArgumentOutOfRangeException(nameof(maximumPlayers));
        CreatedByMembershipId = createdByMembershipId;
        RequiredEntitlement = string.IsNullOrWhiteSpace(requiredEntitlement)
            ? null
            : Require(requiredEntitlement, nameof(requiredEntitlement), 120);
        CreatedAtUtc = createdAtUtc;
        LastActivityAtUtc = createdAtUtc;
        Status = GameSessionStatus.Waiting;
    }

    public Guid Id { get; private set; }
    public string ApplicationKey { get; private set; } = null!;
    public string JoinCode { get; private set; } = null!;
    public string GameType { get; private set; } = null!;
    public string RulesetKey { get; private set; } = null!;
    public int MaximumPlayers { get; private set; }
    public Guid CreatedByMembershipId { get; private set; }
    public string? RequiredEntitlement { get; private set; }
    public GameSessionStatus Status { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset LastActivityAtUtc { get; private set; }
    public DateTimeOffset? StartedAtUtc { get; private set; }
    public DateTimeOffset? CompletedAtUtc { get; private set; }
    public Guid? RematchRequestedByMembershipId { get; private set; }
    public int MatchNumber { get; private set; } = 1;
    public long AggregateVersion { get; private set; }
    public IReadOnlyCollection<GamePlayer> Players => _players;

    public GamePlayer AddPlayer(Guid membershipId, string displayName, DateTimeOffset now)
    {
        if (Status != GameSessionStatus.Waiting || _players.Count >= MaximumPlayers)
        {
            throw new InvalidOperationException("The session is not accepting players.");
        }

        var existing = _players.SingleOrDefault(x => x.MembershipId == membershipId);
        if (existing is not null)
        {
            existing.SetConnected(true, now);
            AdvanceVersion();
            return existing;
        }

        var player = new GamePlayer(
            Guid.NewGuid(),
            Id,
            membershipId,
            displayName,
            _players.Count,
            now);
        _players.Add(player);
        LastActivityAtUtc = now;
        AdvanceVersion();
        return player;
    }

    public bool SetReady(Guid membershipId, DateTimeOffset now)
    {
        return SetReadyCore(membershipId, now, MaximumPlayers);
    }

    public bool SetReadyAndStartWhen(Guid membershipId, DateTimeOffset now, int minimumReadyPlayers)
    {
        if (minimumReadyPlayers < 2 || minimumReadyPlayers > MaximumPlayers)
        {
            throw new ArgumentOutOfRangeException(nameof(minimumReadyPlayers));
        }

        return SetReadyCore(membershipId, now, minimumReadyPlayers);
    }

    private bool SetReadyCore(Guid membershipId, DateTimeOffset now, int minimumReadyPlayers)
    {
        if (Status != GameSessionStatus.Waiting)
        {
            throw new InvalidOperationException("Players can only become ready while the session is waiting.");
        }

        var player = RequirePlayer(membershipId);
        player.SetReady();
        LastActivityAtUtc = now;
        AdvanceVersion();
        if (_players.Count >= minimumReadyPlayers && _players.All(x => x.IsReady))
        {
            Status = GameSessionStatus.Started;
            StartedAtUtc ??= now;
            return true;
        }

        return false;
    }

    public void SetConnection(Guid membershipId, bool connected, DateTimeOffset now)
    {
        RequirePlayer(membershipId).SetConnected(connected, now);
        // Connection changes are also the ordering revision for generic session
        // presence events. Keep it strictly monotonic even when a reconnect and
        // an old connection close are observed in the same clock tick.
        LastActivityAtUtc = now > LastActivityAtUtc ? now : LastActivityAtUtc.AddTicks(1);
        AdvanceVersion();
    }

    public void Complete(DateTimeOffset now)
    {
        Status = GameSessionStatus.Completed;
        CompletedAtUtc = now;
        LastActivityAtUtc = now;
        AdvanceVersion();
    }

    public void RecordActivity(DateTimeOffset now)
    {
        LastActivityAtUtc = now;
        AdvanceVersion();
    }

    public bool RequestRematch(Guid membershipId, DateTimeOffset now)
    {
        if (Status != GameSessionStatus.Completed)
        {
            throw new InvalidOperationException("A rematch can only be requested after completion.");
        }

        RequirePlayer(membershipId);
        if (RematchRequestedByMembershipId == membershipId)
        {
            return false;
        }

        if (RematchRequestedByMembershipId.HasValue)
        {
            throw new InvalidOperationException("Another player already requested a rematch.");
        }

        foreach (var player in _players)
        {
            player.SetReady(player.MembershipId == membershipId);
        }
        RematchRequestedByMembershipId = membershipId;
        LastActivityAtUtc = now;
        AdvanceVersion();
        return true;
    }

    public bool AcceptRematch(Guid membershipId, DateTimeOffset now)
    {
        if (Status != GameSessionStatus.Completed ||
            RematchRequestedByMembershipId is null ||
            RematchRequestedByMembershipId == membershipId)
        {
            throw new InvalidOperationException("A rematch request from the other player is required.");
        }

        RequirePlayer(membershipId).SetReady(true);
        LastActivityAtUtc = now;
        AdvanceVersion();
        if (!_players.All(player => player.IsReady))
        {
            return false;
        }

        Status = GameSessionStatus.Started;
        CompletedAtUtc = null;
        RematchRequestedByMembershipId = null;
        MatchNumber++;
        return true;
    }

    internal void AnonymizeMembership(Guid membershipId, Guid anonymousId)
    {
        foreach (var player in _players.Where(player => player.MembershipId == membershipId))
            player.Anonymize(anonymousId);
        if (RematchRequestedByMembershipId == membershipId)
            RematchRequestedByMembershipId = null;
        // Always advance, even when the only identifying value is held by another
        // aggregate row. Every writer also advances this token, so a stale writer
        // cannot commit a retained reference after anonymization succeeds.
        AdvanceVersion();
    }

    private void AdvanceVersion() => AggregateVersion = checked(AggregateVersion + 1);

    private GamePlayer RequirePlayer(Guid membershipId) =>
        _players.SingleOrDefault(x => x.MembershipId == membershipId)
        ?? throw new UnauthorizedAccessException("The caller is not a session participant.");

    private static string Require(string value, string name, int maxLength)
    {
        var normalized = value?.Trim();
        if (string.IsNullOrWhiteSpace(normalized) || normalized.Length > maxLength)
        {
            throw new ArgumentException($"A valid {name} is required.", name);
        }

        return normalized;
    }
}

public sealed class GamePlayer
{
    private GamePlayer()
    {
    }

    internal GamePlayer(
        Guid id,
        Guid sessionId,
        Guid membershipId,
        string displayName,
        int seat,
        DateTimeOffset joinedAtUtc)
    {
        Id = id;
        SessionId = sessionId;
        MembershipId = membershipId;
        DisplayName = displayName.Trim();
        Seat = seat;
        JoinedAtUtc = joinedAtUtc;
        LastSeenAtUtc = joinedAtUtc;
        IsConnected = true;
    }

    public Guid Id { get; private set; }
    public Guid SessionId { get; private set; }
    public Guid MembershipId { get; private set; }
    public string DisplayName { get; private set; } = null!;
    public int Seat { get; private set; }
    public bool IsReady { get; private set; }
    public bool IsConnected { get; private set; }
    public DateTimeOffset JoinedAtUtc { get; private set; }
    public DateTimeOffset LastSeenAtUtc { get; private set; }

    internal void Anonymize(Guid anonymousId)
    {
        MembershipId = anonymousId;
        DisplayName = "Deleted player";
        IsConnected = false;
    }

    internal void SetReady() => SetReady(true);

    internal void SetReady(bool ready) => IsReady = ready;

    internal void SetConnected(bool connected, DateTimeOffset now)
    {
        IsConnected = connected;
        LastSeenAtUtc = now;
    }
}
