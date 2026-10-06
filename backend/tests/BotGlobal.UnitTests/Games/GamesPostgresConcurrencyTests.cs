using BotGlobal.Games.Domain.Autobus;
using BotGlobal.Games.Domain.Sessions;
using BotGlobal.Games.Domain.Xo;
using BotGlobal.Games.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;
using Npgsql;

namespace BotGlobal.UnitTests.Games;

public sealed class GamesPostgresConcurrencyTests
{
    [Fact]
    public void PostgreSql_model_preserves_concurrency_and_sends_application_tokens()
    {
        using var context = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseNpgsql("Host=localhost;Database=lamma_games_e2e;Username=postgres;Password=unused")
            .Options);
        foreach (var type in new[] { typeof(XoSessionState), typeof(AutobusSessionState) })
        {
            var token = context.Model.FindEntityType(type)!
                .FindProperty(nameof(XoSessionState.ConcurrencyToken))!;
            Assert.True(token.IsConcurrencyToken);
            Assert.Equal(ValueGenerated.Never, token.ValueGenerated);
        }
    }

    [Fact]
    public async Task PostgreSql_creates_and_rotates_Xo_concurrency_token()
    {
        var connectionString = Environment.GetEnvironmentVariable("LAMMA_TEST_POSTGRES");
        if (string.IsNullOrWhiteSpace(connectionString)) return;

        var target = new NpgsqlConnectionStringBuilder(connectionString);
        Assert.Contains(target.Host, new[] { "localhost", "127.0.0.1" });
        Assert.Equal("lamma_games_e2e", target.Database);

        var options = new DbContextOptionsBuilder<GamesDbContext>()
            .UseNpgsql(connectionString)
            .Options;
        var membershipId = Guid.NewGuid();
        var sessionId = Guid.NewGuid();
        await using (var setup = new GamesDbContext(options))
        {
            await setup.Database.EnsureCreatedAsync();
            var session = new GameSession(
                sessionId,
                "family-games",
                Guid.NewGuid().ToString("N")[..8].ToUpperInvariant(),
                "xo",
                XoRuleset.Classic.Key,
                2,
                membershipId,
                DateTimeOffset.UtcNow);
            session.AddPlayer(membershipId, "Test player", DateTimeOffset.UtcNow);
            setup.Sessions.Add(session);
            setup.XoStates.Add(new XoSessionState(sessionId, XoRuleset.Classic));
            await setup.SaveChangesAsync();
        }

        byte[] initialToken;
        await using (var first = new GamesDbContext(options))
        {
            var state = await first.XoStates.SingleAsync(x => x.SessionId == sessionId);
            initialToken = state.ConcurrencyToken.ToArray();
            Assert.Equal(8, initialToken.Length);
            state.Reset(membershipId);
            await first.SaveChangesAsync();
            Assert.Equal(8, state.ConcurrencyToken.Length);
            Assert.NotEqual(initialToken, state.ConcurrencyToken);
        }

        await using (var verify = new GamesDbContext(options))
        {
            var state = await verify.XoStates.SingleAsync(x => x.SessionId == sessionId);
            Assert.NotEqual(initialToken, state.ConcurrencyToken);
        }

        await using var winner = new GamesDbContext(options);
        await using var stale = new GamesDbContext(options);
        var winnerState = await winner.XoStates.SingleAsync(x => x.SessionId == sessionId);
        var staleState = await stale.XoStates.SingleAsync(x => x.SessionId == sessionId);
        winnerState.Reset(Guid.NewGuid());
        staleState.Reset(Guid.NewGuid());
        await winner.SaveChangesAsync();
        await Assert.ThrowsAsync<DbUpdateConcurrencyException>(() => stale.SaveChangesAsync());
    }

    [Fact]
    public async Task PostgreSql_creates_and_rotates_Autobus_concurrency_token()
    {
        var connectionString = Environment.GetEnvironmentVariable("LAMMA_TEST_POSTGRES");
        if (string.IsNullOrWhiteSpace(connectionString)) return;

        var target = new NpgsqlConnectionStringBuilder(connectionString);
        Assert.Contains(target.Host, new[] { "localhost", "127.0.0.1" });
        Assert.Equal("lamma_games_e2e", target.Database);

        var options = new DbContextOptionsBuilder<GamesDbContext>()
            .UseNpgsql(connectionString)
            .Options;
        var ruleset = AutobusRuleset.FromRequest("autobus-5x60-medium", 5, 60, "medium", null);
        var membershipId = Guid.NewGuid();
        var sessionId = Guid.NewGuid();
        await using (var setup = new GamesDbContext(options))
        {
            await setup.Database.EnsureCreatedAsync();
            var session = new GameSession(
                sessionId,
                "family-games",
                Guid.NewGuid().ToString("N")[..8].ToUpperInvariant(),
                "autobus",
                ruleset.Key,
                AutobusRuleset.MaximumPlayers,
                membershipId,
                DateTimeOffset.UtcNow);
            session.AddPlayer(membershipId, "Test player", DateTimeOffset.UtcNow);
            setup.Sessions.Add(session);
            setup.AutobusStates.Add(new AutobusSessionState(sessionId, ruleset));
            await setup.SaveChangesAsync();
        }

        await using var update = new GamesDbContext(options);
        var state = await update.AutobusStates.SingleAsync(x => x.SessionId == sessionId);
        var firstToken = state.ConcurrencyToken.ToArray();
        Assert.Equal(8, firstToken.Length);
        state.StartFirstRound(ruleset, new[] { membershipId }, DateTimeOffset.UtcNow);
        await update.SaveChangesAsync();
        Assert.NotEqual(firstToken, state.ConcurrencyToken);
    }
}
