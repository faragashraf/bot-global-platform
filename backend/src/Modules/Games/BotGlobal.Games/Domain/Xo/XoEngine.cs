namespace BotGlobal.Games.Domain.Xo;

public enum XoMark
{
    None = 0,
    X = 1,
    O = 2
}

public enum XoMatchStatus
{
    InProgress = 0,
    Won = 1,
    Draw = 2
}

public enum XoMoveRejection
{
    None = 0,
    InvalidCoordinate,
    OccupiedCell,
    WrongPlayer,
    NonParticipant,
    MatchCompleted,
    StaleVersion,
    DuplicateCommand
}

public sealed record XoMoveCommand(
    string CommandId,
    Guid PlayerId,
    int Row,
    int Column,
    long ExpectedVersion);

public sealed record XoMoveDecision(
    bool Accepted,
    XoMoveRejection Rejection,
    long Version,
    XoMatchStatus Status,
    Guid? WinnerPlayerId);

public sealed record XoHistoricalMove(
    string CommandId,
    Guid PlayerId,
    int Row,
    int Column);

public sealed class XoEngine
{
    private readonly XoMark[] _board;
    private readonly HashSet<string> _commandIds = new(StringComparer.Ordinal);

    public XoEngine(XoRuleset ruleset, Guid playerXId, Guid playerOId)
    {
        if (playerXId == Guid.Empty || playerOId == Guid.Empty || playerXId == playerOId)
        {
            throw new ArgumentException("Two distinct player identities are required.");
        }

        Ruleset = ruleset;
        PlayerXId = playerXId;
        PlayerOId = playerOId;
        ActivePlayerId = playerXId;
        Status = XoMatchStatus.InProgress;
        _board = new XoMark[ruleset.BoardSize * ruleset.BoardSize];
    }

    public XoRuleset Ruleset { get; }
    public Guid PlayerXId { get; }
    public Guid PlayerOId { get; }
    public Guid ActivePlayerId { get; private set; }
    public Guid? WinnerPlayerId { get; private set; }
    public XoMatchStatus Status { get; private set; }
    public long Version { get; private set; }
    public IReadOnlyList<XoMark> Board => _board;

    public XoMoveDecision Apply(XoMoveCommand command)
    {
        if (string.IsNullOrWhiteSpace(command.CommandId))
        {
            throw new ArgumentException("Command id is required.", nameof(command));
        }

        if (_commandIds.Contains(command.CommandId))
        {
            return Reject(XoMoveRejection.DuplicateCommand);
        }

        if (command.ExpectedVersion != Version)
        {
            return Reject(XoMoveRejection.StaleVersion);
        }

        if (Status != XoMatchStatus.InProgress)
        {
            return Reject(XoMoveRejection.MatchCompleted);
        }

        var mark = MarkFor(command.PlayerId);
        if (mark == XoMark.None)
        {
            return Reject(XoMoveRejection.NonParticipant);
        }

        if (command.PlayerId != ActivePlayerId)
        {
            return Reject(XoMoveRejection.WrongPlayer);
        }

        if (command.Row < 0 || command.Column < 0 ||
            command.Row >= Ruleset.BoardSize || command.Column >= Ruleset.BoardSize)
        {
            return Reject(XoMoveRejection.InvalidCoordinate);
        }

        var index = Index(command.Row, command.Column);
        if (_board[index] != XoMark.None)
        {
            return Reject(XoMoveRejection.OccupiedCell);
        }

        _board[index] = mark;
        _commandIds.Add(command.CommandId);
        Version++;

        if (HasWinningLine(command.Row, command.Column, mark))
        {
            Status = XoMatchStatus.Won;
            WinnerPlayerId = command.PlayerId;
        }
        else if (_board.All(cell => cell != XoMark.None))
        {
            Status = XoMatchStatus.Draw;
        }
        else
        {
            ActivePlayerId = command.PlayerId == PlayerXId ? PlayerOId : PlayerXId;
        }

        return new XoMoveDecision(true, XoMoveRejection.None, Version, Status, WinnerPlayerId);
    }

    public static XoEngine Replay(
        XoRuleset ruleset,
        Guid playerXId,
        Guid playerOId,
        IEnumerable<XoHistoricalMove> moves)
    {
        var engine = new XoEngine(ruleset, playerXId, playerOId);
        foreach (var move in moves)
        {
            var decision = engine.Apply(
                new XoMoveCommand(move.CommandId, move.PlayerId, move.Row, move.Column, engine.Version));
            if (!decision.Accepted)
            {
                throw new InvalidOperationException($"Persisted XO move history is invalid: {decision.Rejection}.");
            }
        }

        return engine;
    }

    private XoMoveDecision Reject(XoMoveRejection rejection) =>
        new(false, rejection, Version, Status, WinnerPlayerId);

    private XoMark MarkFor(Guid playerId) =>
        playerId == PlayerXId ? XoMark.X : playerId == PlayerOId ? XoMark.O : XoMark.None;

    private bool HasWinningLine(int row, int column, XoMark mark)
    {
        ReadOnlySpan<(int Row, int Column)> directions =
        [
            (0, 1),
            (1, 0),
            (1, 1),
            (1, -1)
        ];

        foreach (var direction in directions)
        {
            var count = 1 + Count(row, column, direction.Row, direction.Column, mark)
                + Count(row, column, -direction.Row, -direction.Column, mark);
            if (count >= Ruleset.WinLength)
            {
                return true;
            }
        }

        return false;
    }

    private int Count(int row, int column, int rowStep, int columnStep, XoMark mark)
    {
        var count = 0;
        for (
            int nextRow = row + rowStep, nextColumn = column + columnStep;
            nextRow >= 0 && nextColumn >= 0 &&
            nextRow < Ruleset.BoardSize && nextColumn < Ruleset.BoardSize &&
            _board[Index(nextRow, nextColumn)] == mark;
            nextRow += rowStep, nextColumn += columnStep)
        {
            count++;
        }

        return count;
    }

    private int Index(int row, int column) => row * Ruleset.BoardSize + column;
}
