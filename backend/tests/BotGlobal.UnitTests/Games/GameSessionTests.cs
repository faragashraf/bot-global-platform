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

    [Fact]
    public void Multiplayer_rematch_waits_for_every_player_to_accept()
    {
        var now = DateTimeOffset.UtcNow;
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var third = Guid.NewGuid();
        var session = new GameSession(
            Guid.NewGuid(), "family-games", "ABC123", "autobus", "autobus-5x60-easy", 8, first, now);
        session.AddPlayer(first, "One", now);
        session.AddPlayer(second, "Two", now);
        session.AddPlayer(third, "Three", now);
        session.SetReadyAndStartWhen(first, now, 2);
        session.SetReadyAndStartWhen(second, now, 2);
        session.SetReadyAndStartWhen(third, now, 2);
        session.Complete(now);

        session.RequestRematch(first, now);

        Assert.True(session.Players.Single(player => player.MembershipId == first).IsReady);
        Assert.False(session.Players.Single(player => player.MembershipId == second).IsReady);
        Assert.False(session.Players.Single(player => player.MembershipId == third).IsReady);
        Assert.False(session.AcceptRematch(second, now));
        Assert.Equal(GameSessionStatus.Completed, session.Status);
        Assert.True(session.AcceptRematch(third, now));
        Assert.Equal(GameSessionStatus.Started, session.Status);
        Assert.Equal(2, session.MatchNumber);
    }

    [Fact]
    public void Duplicate_rematch_request_preserves_existing_acceptances()
    {
        var now = DateTimeOffset.UtcNow;
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var third = Guid.NewGuid();
        var session = new GameSession(
            Guid.NewGuid(), "family-games", "ABC123", "autobus", "autobus-5x60-easy", 8, first, now);
        session.AddPlayer(first, "One", now);
        session.AddPlayer(second, "Two", now);
        session.AddPlayer(third, "Three", now);
        session.SetReadyAndStartWhen(first, now, 2);
        session.SetReadyAndStartWhen(second, now, 2);
        session.SetReadyAndStartWhen(third, now, 2);
        session.Complete(now);

        Assert.True(session.RequestRematch(first, now));
        Assert.False(session.AcceptRematch(second, now));
        Assert.False(session.RequestRematch(first, now.AddSeconds(1)));

        Assert.True(session.Players.Single(player => player.MembershipId == second).IsReady);
        Assert.Throws<InvalidOperationException>(() => session.RequestRematch(third, now.AddSeconds(1)));
        Assert.True(session.AcceptRematch(third, now.AddSeconds(2)));
    }

    [Fact]
    public void Delayed_ready_cannot_restart_a_completed_session()
    {
        var now = DateTimeOffset.UtcNow;
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var session = Create(first, now);
        session.AddPlayer(first, "One", now);
        session.AddPlayer(second, "Two", now);
        session.SetReady(first, now);
        session.SetReady(second, now);
        session.Complete(now.AddMinutes(1));

        Assert.Throws<InvalidOperationException>(() => session.SetReady(first, now.AddMinutes(2)));
        Assert.Equal(GameSessionStatus.Completed, session.Status);
    }

    private static GameSession Create(Guid owner, DateTimeOffset now) =>
        new(Guid.NewGuid(), "family-games", "ABC123", "xo", "classic-3x3", 2, owner, now);
}
