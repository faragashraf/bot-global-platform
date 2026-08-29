using System.Security.Cryptography;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application.Entitlements;
using BotGlobal.Games.Domain.Sessions;
using BotGlobal.Games.Domain.Xo;
using BotGlobal.Games.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Games.Application.Sessions;

public interface IGameSessionService
{
    Task<GameCommandResult<GameSessionSnapshot>> CreateAsync(ApplicationIdentityDescriptor identity, CreateGameSessionRequest request, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> JoinAsync(ApplicationIdentityDescriptor identity, JoinGameSessionRequest request, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> ReadyAsync(ApplicationIdentityDescriptor identity, Guid sessionId, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> GetAsync(ApplicationIdentityDescriptor identity, Guid sessionId, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> GetActiveAsync(ApplicationIdentityDescriptor identity, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> MoveAsync(ApplicationIdentityDescriptor identity, XoMoveRequest request, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> RejoinAsync(ApplicationIdentityDescriptor identity, Guid sessionId, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> SetDisconnectedAsync(Guid membershipId, Guid sessionId, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> RequestRematchAsync(ApplicationIdentityDescriptor identity, Guid sessionId, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> AcceptRematchAsync(ApplicationIdentityDescriptor identity, Guid sessionId, CancellationToken cancellationToken);
}

internal sealed class GameSessionService(
    GamesDbContext dbContext,
    IGameEntitlementAuthorizer entitlements,
    IGameRealtimeNotifier realtime,
    IGameNotificationPublisher notifications,
    TimeProvider timeProvider,
    ILogger<GameSessionService> logger) : IGameSessionService
{
    public async Task<GameCommandResult<GameSessionSnapshot>> CreateAsync(
        ApplicationIdentityDescriptor identity,
        CreateGameSessionRequest request,
        CancellationToken cancellationToken)
    {
        XoRuleset ruleset;
        try
        {
            ruleset = XoRuleset.FromKey(request.RulesetKey);
        }
        catch (ArgumentException)
        {
            return Fail("ruleset_invalid", "The requested ruleset is not available.", 400);
        }

        if (!await entitlements.IsAllowedAsync(identity.MembershipId, ruleset.RequiredEntitlement, cancellationToken))
        {
            return Fail("entitlement_required", "The requested game mode is not included in this membership.", 403);
        }

        var now = timeProvider.GetUtcNow();
        var session = new GameSession(
            Guid.NewGuid(),
            BotGlobalApplications.FamilyGames,
            await GenerateCodeAsync(cancellationToken),
            "xo",
            ruleset.Key,
            ruleset.PlayerCount,
            identity.MembershipId,
            now,
            ruleset.RequiredEntitlement);
        session.AddPlayer(identity.MembershipId, identity.DisplayName, now);
        var state = new XoSessionState(session.Id, ruleset);
        dbContext.Sessions.Add(session);
        dbContext.XoStates.Add(state);
        await dbContext.SaveChangesAsync(cancellationToken);

        var snapshot = BuildSnapshot(session, state, [], identity.MembershipId);
        logger.LogInformation(
            "Game session {SessionId} created for application {ApplicationKey} by membership {MembershipId}",
            session.Id,
            session.ApplicationKey,
            identity.MembershipId);
        await realtime.SessionCreatedAsync(snapshot, cancellationToken);
        return GameCommandResult<GameSessionSnapshot>.Success(snapshot, 201);
    }

    public async Task<GameCommandResult<GameSessionSnapshot>> JoinAsync(
        ApplicationIdentityDescriptor identity,
        JoinGameSessionRequest request,
        CancellationToken cancellationToken)
    {
        var code = request.JoinCode?.Trim().ToUpperInvariant();
        if (string.IsNullOrWhiteSpace(code))
        {
            return Fail("join_code_required", "A join code is required.", 400);
        }

        var loaded = await LoadByCodeAsync(identity.ApplicationKey, code, cancellationToken);
        if (loaded is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        var (session, state, moves) = loaded.Value;
        if (!await entitlements.IsAllowedAsync(identity.MembershipId, session.RequiredEntitlement, cancellationToken))
        {
            return Fail("entitlement_required", "The requested game mode is not included in this membership.", 403);
        }

        var alreadyJoined = session.Players.Any(x => x.MembershipId == identity.MembershipId);
        try
        {
            var joinedPlayer = session.AddPlayer(
                identity.MembershipId,
                identity.DisplayName,
                timeProvider.GetUtcNow());
            if (!alreadyJoined)
            {
                dbContext.Players.Add(joinedPlayer);
            }
        }
        catch (InvalidOperationException)
        {
            return Fail("session_not_joinable", "The session is full or has already started.", 409);
        }

        try
        {
            await dbContext.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateException)
        {
            logger.LogWarning(
                "Concurrent join rejected for membership {MembershipId} in session {SessionId}",
                identity.MembershipId,
                session.Id);
            return Fail("session_not_joinable", "The session is full or has already started.", 409);
        }

        var snapshot = BuildSnapshot(session, state, moves, identity.MembershipId);
        logger.LogInformation(
            "Membership {MembershipId} joined game session {SessionId}",
            identity.MembershipId,
            session.Id);
        await realtime.PlayerJoinedAsync(snapshot, cancellationToken);

        var opponent = session.Players.SingleOrDefault(x => x.MembershipId != identity.MembershipId);
        if (opponent is not null)
        {
            await notifications.PublishAsync(
                new GameSemanticNotification(
                    "opponent_joined",
                    opponent.MembershipId,
                    session.Id,
                    $"familygames://sessions/{session.Id}",
                    true),
                cancellationToken);
        }

        return GameCommandResult<GameSessionSnapshot>.Success(snapshot);
    }

    public async Task<GameCommandResult<GameSessionSnapshot>> ReadyAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var loaded = await LoadAsync(identity.ApplicationKey, sessionId, cancellationToken);
        if (loaded is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        var (session, state, moves) = loaded.Value;
        try
        {
            var started = session.SetReady(identity.MembershipId, timeProvider.GetUtcNow());
            if (started)
            {
                state.Reset(session.Players.Single(x => x.Seat == 0).MembershipId);
            }

            await dbContext.SaveChangesAsync(cancellationToken);
            var snapshot = BuildSnapshot(session, state, moves, identity.MembershipId);
            await realtime.PlayerReadyAsync(snapshot, cancellationToken);
            if (started)
            {
                logger.LogInformation("Game session {SessionId} started", session.Id);
                await realtime.GameStartedAsync(snapshot, cancellationToken);
            }

            return GameCommandResult<GameSessionSnapshot>.Success(snapshot);
        }
        catch (UnauthorizedAccessException)
        {
            return Fail("not_participant", "Only a session participant can become ready.", 403);
        }
    }

    public Task<GameCommandResult<GameSessionSnapshot>> GetAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken) =>
        GetAndProjectAsync(identity, sessionId, setConnected: false, cancellationToken);

    public async Task<GameCommandResult<GameSessionSnapshot>> GetActiveAsync(
        ApplicationIdentityDescriptor identity,
        CancellationToken cancellationToken)
    {
        var sessionId = await dbContext.Players
            .Where(x => x.MembershipId == identity.MembershipId)
            .Join(
                dbContext.Sessions.Where(x =>
                    x.ApplicationKey == identity.ApplicationKey &&
                    x.Status != GameSessionStatus.Completed),
                player => player.SessionId,
                session => session.Id,
                (_, session) => new { session.Id, session.LastActivityAtUtc })
            .OrderByDescending(x => x.LastActivityAtUtc)
            .Select(x => (Guid?)x.Id)
            .FirstOrDefaultAsync(cancellationToken);

        return sessionId.HasValue
            ? await GetAsync(identity, sessionId.Value, cancellationToken)
            : Fail("active_session_not_found", "No active game session exists for this membership.", 404);
    }

    public async Task<GameCommandResult<GameSessionSnapshot>> MoveAsync(
        ApplicationIdentityDescriptor identity,
        XoMoveRequest request,
        CancellationToken cancellationToken)
    {
        var loaded = await LoadAsync(identity.ApplicationKey, request.SessionId, cancellationToken);
        if (loaded is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        var (session, state, moves) = loaded.Value;
        if (session.Status != GameSessionStatus.Started)
        {
            return Fail("game_not_active", "The game is not active.", 409);
        }

        var orderedPlayers = session.Players.OrderBy(x => x.Seat).ToArray();
        if (orderedPlayers.Length != 2)
        {
            return Fail("players_incomplete", "Two ready players are required.", 409);
        }

        var engine = Replay(session, state, moves, orderedPlayers);
        var decision = engine.Apply(
            new XoMoveCommand(
                request.CommandId,
                identity.MembershipId,
                request.Row,
                request.Column,
                request.ExpectedVersion));
        if (!decision.Accepted)
        {
            logger.LogWarning(
                "Rejected XO move {CommandId} in session {SessionId}: {Reason}",
                request.CommandId,
                session.Id,
                decision.Rejection);
            return Fail(ToErrorCode(decision.Rejection), "The move was rejected by the authoritative game state.", 409);
        }

        var now = timeProvider.GetUtcNow();
        dbContext.XoMoves.Add(
            new XoMove(
                Guid.NewGuid(),
                session.Id,
                request.CommandId,
                identity.MembershipId,
                request.Row,
                request.Column,
                decision.Version,
                now));
        state.Synchronize(engine);
        session.RecordActivity(now);
        if (engine.Status != XoMatchStatus.InProgress)
        {
            session.Complete(now);
        }

        try
        {
            await dbContext.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateConcurrencyException)
        {
            return Fail("concurrent_move", "Another move updated the game first. Refresh authoritative state.", 409);
        }
        catch (DbUpdateException)
        {
            return Fail("duplicate_or_concurrent_move", "The command was already applied or superseded.", 409);
        }

        var acceptedMoves = moves.Append(dbContext.XoMoves.Local.Last()).OrderBy(x => x.AcceptedVersion).ToArray();
        var snapshot = BuildSnapshot(session, state, acceptedMoves, identity.MembershipId, engine);
        await realtime.MoveAcceptedAsync(snapshot, cancellationToken);
        await realtime.StateUpdatedAsync(snapshot, cancellationToken);

        if (state.ActivePlayerMembershipId.HasValue)
        {
            await notifications.PublishAsync(
                new GameSemanticNotification(
                    "your_turn",
                    state.ActivePlayerMembershipId.Value,
                    session.Id,
                    $"familygames://sessions/{session.Id}",
                    true),
                cancellationToken);
        }

        if (session.Status == GameSessionStatus.Completed)
        {
            logger.LogInformation(
                "Game session {SessionId} completed with status {MatchStatus} and winner {WinnerId}",
                session.Id,
                state.MatchStatus,
                state.WinnerMembershipId);
            await realtime.GameCompletedAsync(snapshot, cancellationToken);
        }

        return GameCommandResult<GameSessionSnapshot>.Success(snapshot);
    }

    public async Task<GameCommandResult<GameSessionSnapshot>> RejoinAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var result = await GetAndProjectAsync(identity, sessionId, setConnected: true, cancellationToken);
        if (result.Succeeded && result.Value is not null)
        {
            await realtime.PlayerConnectionChangedAsync(result.Value, cancellationToken);
        }

        return result;
    }

    public async Task<GameCommandResult<GameSessionSnapshot>> SetDisconnectedAsync(
        Guid membershipId,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var session = await dbContext.Sessions
            .Include(x => x.Players)
            .SingleOrDefaultAsync(x => x.Id == sessionId, cancellationToken);
        if (session is null || session.Players.All(x => x.MembershipId != membershipId))
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        session.SetConnection(membershipId, false, timeProvider.GetUtcNow());
        await dbContext.SaveChangesAsync(cancellationToken);
        var state = await dbContext.XoStates.SingleAsync(x => x.SessionId == sessionId, cancellationToken);
        var moves = await dbContext.XoMoves.Where(x => x.SessionId == sessionId).OrderBy(x => x.AcceptedVersion).ToArrayAsync(cancellationToken);
        var snapshot = BuildSnapshot(session, state, moves, membershipId);
        logger.LogInformation("Membership {MembershipId} disconnected from session {SessionId}", membershipId, sessionId);
        await realtime.PlayerConnectionChangedAsync(snapshot, cancellationToken);
        return GameCommandResult<GameSessionSnapshot>.Success(snapshot);
    }

    public async Task<GameCommandResult<GameSessionSnapshot>> RequestRematchAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var loaded = await LoadAsync(identity.ApplicationKey, sessionId, cancellationToken);
        if (loaded is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        var (session, state, moves) = loaded.Value;
        if (!state.RematchEnabled)
        {
            return Fail("rematch_disabled", "Rematch is disabled for this ruleset.", 409);
        }

        try
        {
            session.RequestRematch(identity.MembershipId, timeProvider.GetUtcNow());
            await dbContext.SaveChangesAsync(cancellationToken);
            var snapshot = BuildSnapshot(session, state, moves, identity.MembershipId);
            await realtime.RematchRequestedAsync(snapshot, cancellationToken);
            return GameCommandResult<GameSessionSnapshot>.Success(snapshot);
        }
        catch (Exception error) when (error is InvalidOperationException or UnauthorizedAccessException)
        {
            return Fail("rematch_invalid", error.Message, 409);
        }
    }

    public async Task<GameCommandResult<GameSessionSnapshot>> AcceptRematchAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var loaded = await LoadAsync(identity.ApplicationKey, sessionId, cancellationToken);
        if (loaded is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        var (session, state, moves) = loaded.Value;
        try
        {
            session.AcceptRematch(identity.MembershipId, timeProvider.GetUtcNow());
            dbContext.XoMoves.RemoveRange(moves);
            state.Reset(session.Players.Single(x => x.Seat == 0).MembershipId);
            await dbContext.SaveChangesAsync(cancellationToken);
            var snapshot = BuildSnapshot(session, state, [], identity.MembershipId);
            await realtime.RematchAcceptedAsync(snapshot, cancellationToken);
            await realtime.GameStartedAsync(snapshot, cancellationToken);
            return GameCommandResult<GameSessionSnapshot>.Success(snapshot);
        }
        catch (Exception error) when (error is InvalidOperationException or UnauthorizedAccessException)
        {
            return Fail("rematch_invalid", error.Message, 409);
        }
    }

    private async Task<GameCommandResult<GameSessionSnapshot>> GetAndProjectAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        bool setConnected,
        CancellationToken cancellationToken)
    {
        var loaded = await LoadAsync(identity.ApplicationKey, sessionId, cancellationToken);
        if (loaded is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        var (session, state, moves) = loaded.Value;
        if (session.Players.All(x => x.MembershipId != identity.MembershipId))
        {
            return Fail("not_participant", "The caller is not a session participant.", 403);
        }

        if (setConnected)
        {
            session.SetConnection(identity.MembershipId, true, timeProvider.GetUtcNow());
            await dbContext.SaveChangesAsync(cancellationToken);
            logger.LogInformation("Membership {MembershipId} rejoined session {SessionId}", identity.MembershipId, sessionId);
        }

        return GameCommandResult<GameSessionSnapshot>.Success(
            BuildSnapshot(session, state, moves, identity.MembershipId));
    }

    private async Task<(GameSession Session, XoSessionState State, IReadOnlyList<XoMove> Moves)?> LoadAsync(
        string applicationKey,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var session = await dbContext.Sessions
            .Include(x => x.Players)
            .SingleOrDefaultAsync(
                x => x.Id == sessionId && x.ApplicationKey == applicationKey,
                cancellationToken);
        if (session is null)
        {
            return null;
        }

        var state = await dbContext.XoStates.SingleAsync(x => x.SessionId == session.Id, cancellationToken);
        var moves = await dbContext.XoMoves
            .Where(x => x.SessionId == session.Id)
            .OrderBy(x => x.AcceptedVersion)
            .ToArrayAsync(cancellationToken);
        return (session, state, moves);
    }

    private async Task<(GameSession Session, XoSessionState State, IReadOnlyList<XoMove> Moves)?> LoadByCodeAsync(
        string applicationKey,
        string joinCode,
        CancellationToken cancellationToken)
    {
        var sessionId = await dbContext.Sessions
            .Where(x => x.ApplicationKey == applicationKey && x.JoinCode == joinCode)
            .Select(x => (Guid?)x.Id)
            .SingleOrDefaultAsync(cancellationToken);
        return sessionId.HasValue
            ? await LoadAsync(applicationKey, sessionId.Value, cancellationToken)
            : null;
    }

    private static GameSessionSnapshot BuildSnapshot(
        GameSession session,
        XoSessionState state,
        IReadOnlyList<XoMove> moves,
        Guid _,
        XoEngine? currentEngine = null)
    {
        var orderedPlayers = session.Players.OrderBy(x => x.Seat).ToArray();
        var engine = currentEngine;
        if (engine is null && orderedPlayers.Length == 2)
        {
            engine = Replay(session, state, moves, orderedPlayers);
        }

        var board = engine?.Board.Select(x => x switch
        {
            XoMark.X => "x",
            XoMark.O => "o",
            _ => string.Empty
        }).ToArray() ?? Enumerable.Repeat(string.Empty, state.BoardSize * state.BoardSize).ToArray();

        return new GameSessionSnapshot(
            session.Id,
            session.JoinCode,
            session.GameType,
            session.Status.ToString().ToLowerInvariant(),
            session.MatchNumber,
            new GameRulesetSnapshot(
                session.RulesetKey,
                state.BoardSize,
                state.WinLength,
                session.MaximumPlayers,
                state.TurnTimeLimitSeconds,
                state.RematchEnabled,
                state.VoiceEnabled,
                state.RequiredEntitlement),
            orderedPlayers.Select(player =>
                new GamePlayerSnapshot(
                    player.MembershipId,
                    player.DisplayName,
                    player.Seat,
                    player.Seat == 0 ? "x" : "o",
                    player.IsReady,
                    player.IsConnected)).ToArray(),
            board,
            state.Version,
            state.ActivePlayerMembershipId,
            state.WinnerMembershipId,
            state.MatchStatus.ToString().ToLowerInvariant(),
            session.RematchRequestedByMembershipId,
            session.LastActivityAtUtc,
            session.LastActivityAtUtc.UtcDateTime.Ticks);
    }

    private static XoEngine Replay(
        GameSession session,
        XoSessionState state,
        IReadOnlyList<XoMove> moves,
        IReadOnlyList<GamePlayer> orderedPlayers) =>
        XoEngine.Replay(
            state.ToRuleset(session.RulesetKey),
            orderedPlayers[0].MembershipId,
            orderedPlayers[1].MembershipId,
            moves.Select(x =>
                new XoHistoricalMove(x.CommandId, x.PlayerMembershipId, x.Row, x.Column)));

    private async Task<string> GenerateCodeAsync(CancellationToken cancellationToken)
    {
        const string alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (var attempt = 0; attempt < 20; attempt++)
        {
            var bytes = RandomNumberGenerator.GetBytes(6);
            var code = new string(bytes.Select(value => alphabet[value % alphabet.Length]).ToArray());
            if (!await dbContext.Sessions.AnyAsync(
                x => x.ApplicationKey == BotGlobalApplications.FamilyGames && x.JoinCode == code,
                cancellationToken))
            {
                return code;
            }
        }

        throw new InvalidOperationException("A unique game join code could not be generated.");
    }

    private static string ToErrorCode(XoMoveRejection rejection) => rejection switch
    {
        XoMoveRejection.InvalidCoordinate => "invalid_coordinate",
        XoMoveRejection.OccupiedCell => "occupied_cell",
        XoMoveRejection.WrongPlayer => "wrong_player",
        XoMoveRejection.NonParticipant => "not_participant",
        XoMoveRejection.MatchCompleted => "game_completed",
        XoMoveRejection.StaleVersion => "stale_version",
        XoMoveRejection.DuplicateCommand => "duplicate_command",
        _ => "move_rejected"
    };

    private static GameCommandResult<GameSessionSnapshot> Fail(string code, string message, int statusCode) =>
        GameCommandResult<GameSessionSnapshot>.Failure(code, message, statusCode);
}
