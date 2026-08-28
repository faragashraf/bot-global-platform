namespace BotGlobal.Games.Domain.Xo;

public sealed class XoSessionState
{
    private XoSessionState()
    {
    }

    public XoSessionState(Guid sessionId, XoRuleset ruleset)
    {
        SessionId = sessionId;
        BoardSize = ruleset.BoardSize;
        WinLength = ruleset.WinLength;
        TurnTimeLimitSeconds = ruleset.TurnTimeLimit is null
            ? null
            : (int)ruleset.TurnTimeLimit.Value.TotalSeconds;
        RematchEnabled = ruleset.RematchEnabled;
        VoiceEnabled = ruleset.VoiceEnabled;
        RequiredEntitlement = ruleset.RequiredEntitlement;
        MatchStatus = XoMatchStatus.InProgress;
    }

    public Guid SessionId { get; private set; }
    public int BoardSize { get; private set; }
    public int WinLength { get; private set; }
    public int? TurnTimeLimitSeconds { get; private set; }
    public bool RematchEnabled { get; private set; }
    public bool VoiceEnabled { get; private set; }
    public string? RequiredEntitlement { get; private set; }
    public long Version { get; private set; }
    public XoMatchStatus MatchStatus { get; private set; }
    public Guid? ActivePlayerMembershipId { get; private set; }
    public Guid? WinnerMembershipId { get; private set; }
    public byte[] ConcurrencyToken { get; private set; } = [];

    public void Synchronize(XoEngine engine)
    {
        Version = engine.Version;
        MatchStatus = engine.Status;
        ActivePlayerMembershipId = engine.Status == XoMatchStatus.InProgress
            ? engine.ActivePlayerId
            : null;
        WinnerMembershipId = engine.WinnerPlayerId;
    }

    public void Reset(Guid firstPlayerMembershipId)
    {
        Version = 0;
        MatchStatus = XoMatchStatus.InProgress;
        ActivePlayerMembershipId = firstPlayerMembershipId;
        WinnerMembershipId = null;
    }

    public XoRuleset ToRuleset(string key) =>
        new(
            key,
            BoardSize,
            WinLength,
            2,
            TurnTimeLimitSeconds.HasValue ? TimeSpan.FromSeconds(TurnTimeLimitSeconds.Value) : null,
            RematchEnabled,
            VoiceEnabled,
            RequiredEntitlement);
}

public sealed class XoMove
{
    private XoMove()
    {
    }

    public XoMove(
        Guid id,
        Guid sessionId,
        string commandId,
        Guid playerMembershipId,
        int row,
        int column,
        long acceptedVersion,
        DateTimeOffset acceptedAtUtc)
    {
        Id = id;
        SessionId = sessionId;
        CommandId = commandId.Trim();
        PlayerMembershipId = playerMembershipId;
        Row = row;
        Column = column;
        AcceptedVersion = acceptedVersion;
        AcceptedAtUtc = acceptedAtUtc;
    }

    public Guid Id { get; private set; }
    public Guid SessionId { get; private set; }
    public string CommandId { get; private set; } = null!;
    public Guid PlayerMembershipId { get; private set; }
    public int Row { get; private set; }
    public int Column { get; private set; }
    public long AcceptedVersion { get; private set; }
    public DateTimeOffset AcceptedAtUtc { get; private set; }
}
