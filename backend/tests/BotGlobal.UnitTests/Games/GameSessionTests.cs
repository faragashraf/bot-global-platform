using BotGlobal.Games.Domain.Sessions;

namespace BotGlobal.UnitTests.Games;

public sealed class GameSessionTests
{
    [Fact]
    public void Starts_only_when_two_players_are_ready()
    {
        var now = DateTimeOffset.UtcNow;
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var session = Create(first, now);
        session.AddPlayer(first, "Player one", now);
        session.AddPlayer(second, "Player two", now);

        Assert.False(session.SetReady(first, now));
        Assert.Equal(GameSessionStatus.Waiting, session.Status);
        Assert.True(session.SetReady(second, now));
        Assert.Equal(GameSessionStatus.Started, session.Status);
    }

    [Fact]
    public void Rematch_requires_other_player_acceptance()
    {
        var now = DateTimeOffset.UtcNow;
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var session = Create(first, now);
        session.AddPlayer(first, "One", now);
        session.AddPlayer(second, "Two", now);
        session.SetReady(first, now);
        session.SetReady(second, now);
        session.Complete(now);
        session.RequestRematch(first, now);

        Assert.Throws<InvalidOperationException>(() => session.AcceptRematch(first, now));
        session.AcceptRematch(second, now);

        Assert.Equal(GameSessionStatus.Started, session.Status);
        Assert.Equal(2, session.MatchNumber);
    }

    private static GameSession Create(Guid owner, DateTimeOffset now) =>
        new(Guid.NewGuid(), "family-games", "ABC123", "xo", "classic-3x3", 2, owner, now);
}
