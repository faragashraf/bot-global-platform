using BotGlobal.Games.Domain.Xo;

namespace BotGlobal.UnitTests.Games;

public sealed class XoEngineTests
{
    private readonly Guid _x = Guid.NewGuid();
    private readonly Guid _o = Guid.NewGuid();

    [Fact]
    public void Detects_horizontal_win()
    {
        var engine = Classic();
        Play(engine, (_x, 0, 0), (_o, 1, 0), (_x, 0, 1), (_o, 1, 1));
        var result = Move(engine, _x, 0, 2);
        Assert.Equal(XoMatchStatus.Won, result.Status);
        Assert.Equal(_x, result.WinnerPlayerId);
    }

    [Fact]
    public void Detects_vertical_win()
    {
        var engine = Classic();
        Play(engine, (_x, 0, 0), (_o, 0, 1), (_x, 1, 0), (_o, 1, 1));
        Assert.Equal(XoMatchStatus.Won, Move(engine, _x, 2, 0).Status);
    }

    [Fact]
    public void Detects_main_diagonal_win()
    {
        var engine = Classic();
        Play(engine, (_x, 0, 0), (_o, 0, 1), (_x, 1, 1), (_o, 0, 2));
        Assert.Equal(XoMatchStatus.Won, Move(engine, _x, 2, 2).Status);
    }

    [Fact]
    public void Detects_opposite_diagonal_win()
    {
        var engine = Classic();
        Play(engine, (_x, 0, 2), (_o, 0, 0), (_x, 1, 1), (_o, 1, 0));
        Assert.Equal(XoMatchStatus.Won, Move(engine, _x, 2, 0).Status);
    }

    [Fact]
    public void Detects_draw()
    {
        var engine = Classic();
        Play(
            engine,
            (_x, 0, 0), (_o, 0, 1), (_x, 0, 2),
            (_o, 1, 1), (_x, 1, 0), (_o, 1, 2),
            (_x, 2, 1), (_o, 2, 0));
        Assert.Equal(XoMatchStatus.Draw, Move(engine, _x, 2, 2).Status);
    }

    [Fact]
    public void Rejects_occupied_cell()
    {
        var engine = Classic();
        Move(engine, _x, 0, 0);
        Assert.Equal(XoMoveRejection.OccupiedCell, Move(engine, _o, 0, 0).Rejection);
    }

    [Fact]
    public void Rejects_wrong_player()
    {
        Assert.Equal(XoMoveRejection.WrongPlayer, Move(Classic(), _o, 0, 0).Rejection);
    }

    [Fact]
    public void Rejects_invalid_coordinate()
    {
        Assert.Equal(XoMoveRejection.InvalidCoordinate, Move(Classic(), _x, -1, 0).Rejection);
    }

    [Fact]
    public void Rejects_move_after_completion()
    {
        var engine = Classic();
        Play(engine, (_x, 0, 0), (_o, 1, 0), (_x, 0, 1), (_o, 1, 1), (_x, 0, 2));
        Assert.Equal(XoMoveRejection.MatchCompleted, Move(engine, _o, 2, 2).Rejection);
    }

    [Fact]
    public void Rejects_stale_version()
    {
        var engine = Classic();
        Move(engine, _x, 0, 0);
        var result = engine.Apply(new XoMoveCommand("stale", _o, 0, 1, 0));
        Assert.Equal(XoMoveRejection.StaleVersion, result.Rejection);
    }

    [Fact]
    public void Rejects_duplicate_command_before_version_check()
    {
        var engine = Classic();
        var command = new XoMoveCommand("same-command", _x, 0, 0, 0);
        Assert.True(engine.Apply(command).Accepted);
        Assert.Equal(XoMoveRejection.DuplicateCommand, engine.Apply(command).Rejection);
    }

    [Fact]
    public void Supports_larger_five_by_five_win_four_ruleset()
    {
        var engine = new XoEngine(XoRuleset.Extended, _x, _o);
        Play(
            engine,
            (_x, 0, 0), (_o, 4, 0),
            (_x, 1, 1), (_o, 4, 1),
            (_x, 2, 2), (_o, 4, 2));
        Assert.Equal(XoMatchStatus.Won, Move(engine, _x, 3, 3).Status);
    }

    private XoEngine Classic() => new(XoRuleset.Classic, _x, _o);

    private static void Play(XoEngine engine, params (Guid Player, int Row, int Column)[] moves)
    {
        foreach (var move in moves)
        {
            Assert.True(Move(engine, move.Player, move.Row, move.Column).Accepted);
        }
    }

    private static XoMoveDecision Move(XoEngine engine, Guid player, int row, int column) =>
        engine.Apply(new XoMoveCommand(Guid.NewGuid().ToString("N"), player, row, column, engine.Version));
}
