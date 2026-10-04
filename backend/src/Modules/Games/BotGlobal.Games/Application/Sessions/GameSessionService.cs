using System.Security.Cryptography;
using System.Globalization;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application.Entitlements;
using BotGlobal.Games.Domain.Autobus;
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
    Task<GameCommandResult<GameSessionSnapshot>> SubmitAutobusAnswersAsync(ApplicationIdentityDescriptor identity, AutobusSubmitAnswersRequest request, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> FinishAutobusRoundAsync(ApplicationIdentityDescriptor identity, AutobusFinishRoundRequest request, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> RevealAutobusAsync(ApplicationIdentityDescriptor identity, AutobusRevealRequest request, CancellationToken cancellationToken);
    Task<GameCommandResult<GameSessionSnapshot>> VoteAutobusAsync(ApplicationIdentityDescriptor identity, AutobusVoteRequest request, CancellationToken cancellationToken);
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
    ILogger<GameSessionService> logger,
    IGamesMembershipWriteFence? membershipWriteFence = null) : IGameSessionService
{
    private readonly IGamesMembershipWriteFence _membershipWriteFence = membershipWriteFence ??
        new GamesMembershipWriteFence(dbContext, new GamesMembershipFenceLock());

    public Task<GameCommandResult<GameSessionSnapshot>> CreateAsync(
        ApplicationIdentityDescriptor identity,
        CreateGameSessionRequest request,
        CancellationToken cancellationToken) =>
        ExecuteMembershipWriteAsync(
            BotGlobalApplications.FamilyGames,
            identity.MembershipId,
            () => CreateCoreAsync(identity, request, cancellationToken),
            cancellationToken);

    private async Task<GameCommandResult<GameSessionSnapshot>> CreateCoreAsync(
        ApplicationIdentityDescriptor identity,
        CreateGameSessionRequest request,
        CancellationToken cancellationToken)
    {
        if (IsAutobusRequest(request))
        {
            return await CreateAutobusCoreAsync(identity, request, cancellationToken);
        }

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

        var snapshot = BuildXoSnapshot(session, state, [], identity.MembershipId);
        logger.LogInformation(
            "Game session {SessionId} created for application {ApplicationKey} by membership {MembershipId}",
            session.Id,
            session.ApplicationKey,
            identity.MembershipId);
        await realtime.SessionCreatedAsync(ForRealtime(snapshot), cancellationToken);
        return GameCommandResult<GameSessionSnapshot>.Success(snapshot, 201);
    }

    private async Task<GameCommandResult<GameSessionSnapshot>> CreateAutobusCoreAsync(
        ApplicationIdentityDescriptor identity,
        CreateGameSessionRequest request,
        CancellationToken cancellationToken)
    {
        var ruleset = AutobusRuleset.FromRequest(
            request.RulesetKey,
            request.RoundCount,
            request.RoundSeconds,
            request.Difficulty,
            request.Categories);
        var now = timeProvider.GetUtcNow();
        var session = new GameSession(
            Guid.NewGuid(),
            BotGlobalApplications.FamilyGames,
            await GenerateCodeAsync(cancellationToken),
            "autobus",
            ruleset.Key,
            AutobusRuleset.MaximumPlayers,
            identity.MembershipId,
            now);
        session.AddPlayer(identity.MembershipId, identity.DisplayName, now);
        var state = new AutobusSessionState(session.Id, ruleset);
        dbContext.Sessions.Add(session);
        dbContext.AutobusStates.Add(state);
        await dbContext.SaveChangesAsync(cancellationToken);

        var snapshot = BuildAutobusSnapshot(session, state, identity.MembershipId);
        logger.LogInformation(
            "Autobus session {SessionId} created for application {ApplicationKey} by membership {MembershipId}",
            session.Id,
            session.ApplicationKey,
            identity.MembershipId);
        await realtime.SessionCreatedAsync(ForRealtime(snapshot), cancellationToken);
        return GameCommandResult<GameSessionSnapshot>.Success(snapshot, 201);
    }

    public async Task<GameCommandResult<GameSessionSnapshot>> JoinAsync(
        ApplicationIdentityDescriptor identity,
        JoinGameSessionRequest request,
        CancellationToken cancellationToken)
    {
        var persisted = await _membershipWriteFence.ExecuteAsync(
            identity.ApplicationKey,
            identity.MembershipId,
            () => JoinWithinMembershipWriteAsync(identity, request, cancellationToken),
            () => PersistedJoinResult.Failed(
                Fail("account_deleted", "The game membership has been deleted.", 410)),
            cancellationToken);

        if (persisted.PlayerAdded && persisted.Result.Succeeded)
        {
            await PublishJoinSideEffectsAsync(persisted.Result.Value!, identity.MembershipId, cancellationToken);
        }

        return persisted.Result;
    }

    internal async Task<PersistedJoinResult> JoinWithinMembershipWriteAsync(
        ApplicationIdentityDescriptor identity,
        JoinGameSessionRequest request,
        CancellationToken cancellationToken)
    {
        var code = request.JoinCode?.Trim().ToUpperInvariant();
        if (string.IsNullOrWhiteSpace(code))
        {
            return PersistedJoinResult.Failed(Fail("join_code_required", "A join code is required.", 400));
        }

        var session = await LoadSessionByCodeAsync(identity.ApplicationKey, code, cancellationToken);
        if (session is null)
        {
            return PersistedJoinResult.Failed(Fail("session_not_found", "The game session was not found.", 404));
        }

        if (!await entitlements.IsAllowedAsync(identity.MembershipId, session.RequiredEntitlement, cancellationToken))
        {
            return PersistedJoinResult.Failed(Fail("entitlement_required", "The requested game mode is not included in this membership.", 403));
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
            return PersistedJoinResult.Failed(Fail("session_not_joinable", "The session is full or has already started.", 409));
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
            return PersistedJoinResult.Failed(Fail("session_not_joinable", "The session is full or has already started.", 409));
        }

        var snapshot = await BuildSnapshotAsync(session, identity.MembershipId, cancellationToken);
        logger.LogInformation(
            "Membership {MembershipId} joined game session {SessionId}",
            identity.MembershipId,
            session.Id);
        return new PersistedJoinResult(
            GameCommandResult<GameSessionSnapshot>.Success(snapshot),
            !alreadyJoined);
    }

    internal async Task PublishJoinSideEffectsAsync(
        GameSessionSnapshot snapshot,
        Guid joinedMembershipId,
        CancellationToken cancellationToken)
    {
        await realtime.PlayerJoinedAsync(ForRealtime(snapshot), cancellationToken);
        foreach (var player in snapshot.Players.Where(x => x.MembershipId != joinedMembershipId))
        {
            await notifications.PublishAsync(
                new GameSemanticNotification(
                    "opponent_joined",
                    player.MembershipId,
                    snapshot.SessionId,
                    $"familygames://sessions/{snapshot.SessionId}",
                    true),
                cancellationToken);
        }
    }

    public Task<GameCommandResult<GameSessionSnapshot>> ReadyAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken) =>
        ExecuteMembershipWriteAsync(
            identity.ApplicationKey,
            identity.MembershipId,
            () => ReadyCoreAsync(identity, sessionId, cancellationToken),
            cancellationToken);

    private async Task<GameCommandResult<GameSessionSnapshot>> ReadyCoreAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var session = await LoadSessionAsync(identity.ApplicationKey, sessionId, cancellationToken);
        if (session is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        try
        {
            var now = timeProvider.GetUtcNow();
            var started = session.GameType == "autobus"
                ? session.SetReadyAndStartWhen(identity.MembershipId, now, AutobusRuleset.MinimumPlayers)
                : session.SetReady(identity.MembershipId, now);
            if (started && session.GameType == "xo")
            {
                var state = await dbContext.XoStates.SingleAsync(x => x.SessionId == session.Id, cancellationToken);
                state.Reset(session.Players.Single(x => x.Seat == 0).MembershipId);
            }
            else if (started && session.GameType == "autobus")
            {
                var state = await dbContext.AutobusStates.SingleAsync(x => x.SessionId == session.Id, cancellationToken);
                state.StartFirstRound(
                    state.ToRuleset(session.RulesetKey),
                    session.Players.Select(x => x.MembershipId).ToArray(),
                    now);
            }

            await dbContext.SaveChangesAsync(cancellationToken);
            var snapshot = await BuildSnapshotAsync(session, identity.MembershipId, cancellationToken);
            await realtime.PlayerReadyAsync(ForRealtime(snapshot), cancellationToken);
            if (started)
            {
                logger.LogInformation("Game session {SessionId} started", session.Id);
                await realtime.GameStartedAsync(ForRealtime(snapshot), cancellationToken);
            }

            return GameCommandResult<GameSessionSnapshot>.Success(snapshot);
        }
        catch (UnauthorizedAccessException)
        {
            return Fail("not_participant", "Only a session participant can become ready.", 403);
        }
        catch (InvalidOperationException error)
        {
            return Fail("session_not_waiting", error.Message, 409);
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

    public Task<GameCommandResult<GameSessionSnapshot>> MoveAsync(
        ApplicationIdentityDescriptor identity,
        XoMoveRequest request,
        CancellationToken cancellationToken) =>
        ExecuteMembershipWriteAsync(
            identity.ApplicationKey,
            identity.MembershipId,
            () => MoveCoreAsync(identity, request, cancellationToken),
            cancellationToken);

    private async Task<GameCommandResult<GameSessionSnapshot>> MoveCoreAsync(
        ApplicationIdentityDescriptor identity,
        XoMoveRequest request,
        CancellationToken cancellationToken)
    {
        var loaded = await LoadXoAsync(identity.ApplicationKey, request.SessionId, cancellationToken);
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
        var snapshot = BuildXoSnapshot(session, state, acceptedMoves, identity.MembershipId, engine);
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

    public Task<GameCommandResult<GameSessionSnapshot>> SubmitAutobusAnswersAsync(
        ApplicationIdentityDescriptor identity,
        AutobusSubmitAnswersRequest request,
        CancellationToken cancellationToken) =>
        ExecuteMembershipWriteAsync(
            identity.ApplicationKey,
            identity.MembershipId,
            () => SubmitAutobusAnswersCoreAsync(identity, request, cancellationToken),
            cancellationToken);

    private async Task<GameCommandResult<GameSessionSnapshot>> SubmitAutobusAnswersCoreAsync(
        ApplicationIdentityDescriptor identity,
        AutobusSubmitAnswersRequest request,
        CancellationToken cancellationToken)
    {
        const int maximumAttempts = 3;
        for (var attempt = 1; attempt <= maximumAttempts; attempt++)
        {
            var result = await AutobusCommandCoreAsync(
                identity,
                request.SessionId,
                request.CommandId,
                request.ExpectedVersion,
                "submit",
                (session, state, now) =>
                {
                    if (session.MatchNumber != request.MatchNumber || state.CurrentRound != request.Round)
                    {
                        throw new InvalidOperationException("The Autobus submission belongs to an older match or round.");
                    }

                    state.Submit(identity.MembershipId, request.Answers, now);
                    session.RecordActivity(now);
                    return false;
                },
                realtime.StateUpdatedAsync,
                cancellationToken,
                allowStaleVersion: true);
            if (result.Succeeded ||
                result.ErrorCode is not ("concurrent_command" or "duplicate_or_concurrent_command") ||
                attempt == maximumAttempts)
            {
                return result;
            }

            dbContext.ChangeTracker.Clear();
        }

        throw new InvalidOperationException("The Autobus answer retry loop ended unexpectedly.");
    }

    public Task<GameCommandResult<GameSessionSnapshot>> FinishAutobusRoundAsync(
        ApplicationIdentityDescriptor identity,
        AutobusFinishRoundRequest request,
        CancellationToken cancellationToken) =>
        ExecuteMembershipWriteAsync(
            identity.ApplicationKey,
            identity.MembershipId,
            () => AutobusCommandCoreAsync(
                identity,
                request.SessionId,
                request.CommandId,
                request.ExpectedVersion,
                "finish",
                (session, state, now) =>
                {
                    AdvanceAutobusGraceOrReveal(session, state, now);

                    session.RecordActivity(now);
                    return false;
                },
                realtime.StateUpdatedAsync,
                cancellationToken),
            cancellationToken);

    public Task<GameCommandResult<GameSessionSnapshot>> RevealAutobusAsync(
        ApplicationIdentityDescriptor identity,
        AutobusRevealRequest request,
        CancellationToken cancellationToken) =>
        ExecuteMembershipWriteAsync(
            identity.ApplicationKey,
            identity.MembershipId,
            () => AutobusCommandCoreAsync(
                identity,
                request.SessionId,
                request.CommandId,
                request.ExpectedVersion,
                "reveal",
                (session, state, now) =>
                {
                    if (state.Phase is AutobusRoundPhase.Active or AutobusRoundPhase.Grace)
                    {
                        AdvanceAutobusGraceOrReveal(session, state, now);
                        session.RecordActivity(now);
                        return false;
                    }

                    var completed = state.AdvanceRevealOrRound(
                        state.ToRuleset(session.RulesetKey),
                        session.Players.Select(x => x.MembershipId).ToArray(),
                        session.Players.Where(x => x.IsConnected).Select(x => x.MembershipId).ToArray(),
                        now);
                    if (completed)
                    {
                        session.Complete(now);
                    }
                    else
                    {
                        session.RecordActivity(now);
                    }

                    return completed;
                },
                realtime.StateUpdatedAsync,
                cancellationToken),
            cancellationToken);

    public Task<GameCommandResult<GameSessionSnapshot>> VoteAutobusAsync(
        ApplicationIdentityDescriptor identity,
        AutobusVoteRequest request,
        CancellationToken cancellationToken) =>
        ExecuteMembershipWriteAsync(
            identity.ApplicationKey,
            identity.MembershipId,
            () => AutobusCommandCoreAsync(
                identity,
                request.SessionId,
                request.CommandId,
                request.ExpectedVersion,
                "vote",
                (session, state, now) =>
                {
                    state.Vote(
                        identity.MembershipId,
                        request.AnswerOwnerMembershipId,
                        request.CategoryKey,
                        request.Accept,
                        session.Players.Where(x => x.IsConnected).Select(x => x.MembershipId).ToArray(),
                        now);
                    session.RecordActivity(now);
                    return false;
                },
                realtime.StateUpdatedAsync,
                cancellationToken),
            cancellationToken);

    public Task<GameCommandResult<GameSessionSnapshot>> RejoinAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken) =>
        ExecuteMembershipWriteAsync(
            identity.ApplicationKey,
            identity.MembershipId,
            () => RejoinCoreAsync(identity, sessionId, cancellationToken),
            cancellationToken);

    private async Task<GameCommandResult<GameSessionSnapshot>> RejoinCoreAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var result = await GetAndProjectAsync(identity, sessionId, setConnected: true, cancellationToken);
        if (result.Succeeded && result.Value is not null)
        {
            await realtime.PlayerConnectionChangedAsync(ForRealtime(result.Value), cancellationToken);
        }

        return result;
    }

    public Task<GameCommandResult<GameSessionSnapshot>> SetDisconnectedAsync(
        Guid membershipId,
        Guid sessionId,
        CancellationToken cancellationToken) =>
        ExecuteMembershipWriteAsync(
            BotGlobalApplications.FamilyGames,
            membershipId,
            () => SetDisconnectedCoreAsync(membershipId, sessionId, cancellationToken),
            cancellationToken);

    private async Task<GameCommandResult<GameSessionSnapshot>> SetDisconnectedCoreAsync(
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
        var snapshot = await BuildSnapshotAsync(session, null, cancellationToken);
        logger.LogInformation("Membership {MembershipId} disconnected from session {SessionId}", membershipId, sessionId);
        await realtime.PlayerConnectionChangedAsync(ForRealtime(snapshot), cancellationToken);
        return GameCommandResult<GameSessionSnapshot>.Success(snapshot);
    }

    public async Task<GameCommandResult<GameSessionSnapshot>> RequestRematchAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        const int maximumAttempts = 3;
        for (var attempt = 1; attempt <= maximumAttempts; attempt++)
        {
            try
            {
                return await ExecuteMembershipWriteAsync(
                    identity.ApplicationKey,
                    identity.MembershipId,
                    () => RequestRematchCoreAsync(identity, sessionId, cancellationToken),
                    cancellationToken);
            }
            catch (DbUpdateConcurrencyException)
            {
                if (attempt == maximumAttempts)
                {
                    return Fail("concurrent_rematch", "Another player updated the rematch first. Try again.", 409);
                }

                dbContext.ChangeTracker.Clear();
            }
        }

        throw new InvalidOperationException("The rematch request retry loop ended unexpectedly.");
    }

    private async Task<GameCommandResult<GameSessionSnapshot>> RequestRematchCoreAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var session = await LoadSessionAsync(identity.ApplicationKey, sessionId, cancellationToken);
        if (session is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        if (session.GameType == "xo")
        {
            var state = await dbContext.XoStates.SingleAsync(x => x.SessionId == session.Id, cancellationToken);
            if (!state.RematchEnabled)
            {
                return Fail("rematch_disabled", "Rematch is disabled for this ruleset.", 409);
            }
        }

        try
        {
            var requested = session.RequestRematch(identity.MembershipId, timeProvider.GetUtcNow());
            if (!requested)
            {
                return GameCommandResult<GameSessionSnapshot>.Success(
                    await BuildSnapshotAsync(session, identity.MembershipId, cancellationToken));
            }

            await dbContext.SaveChangesAsync(cancellationToken);
            var snapshot = await BuildSnapshotAsync(session, identity.MembershipId, cancellationToken);
            await realtime.RematchRequestedAsync(ForRealtime(snapshot), cancellationToken);
            return GameCommandResult<GameSessionSnapshot>.Success(snapshot);
        }
        catch (Exception error) when (error is InvalidOperationException or UnauthorizedAccessException)
        {
            return Fail("rematch_invalid", error.Message, 409);
        }
    }

    private static void AdvanceAutobusGraceOrReveal(
        GameSession session,
        AutobusSessionState state,
        DateTimeOffset now)
    {
        if (state.Phase == AutobusRoundPhase.Active)
        {
            state.StartGrace(now);
        }

        if (state.Phase == AutobusRoundPhase.Grace &&
            state.GraceEndsAtUtc.HasValue &&
            now >= state.GraceEndsAtUtc.Value)
        {
            state.LockForReveal(
                session.Players.Select(x => x.MembershipId).ToArray(),
                new AutobusAnswerValidator(),
                now);
        }
    }

    internal sealed record PersistedJoinResult(
        GameCommandResult<GameSessionSnapshot> Result,
        bool PlayerAdded)
    {
        public static PersistedJoinResult Failed(GameCommandResult<GameSessionSnapshot> result) =>
            new(result, false);
    }

    public async Task<GameCommandResult<GameSessionSnapshot>> AcceptRematchAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        const int maximumAttempts = 3;
        for (var attempt = 1; attempt <= maximumAttempts; attempt++)
        {
            try
            {
                return await ExecuteMembershipWriteAsync(
                    identity.ApplicationKey,
                    identity.MembershipId,
                    () => AcceptRematchCoreAsync(identity, sessionId, cancellationToken),
                    cancellationToken);
            }
            catch (DbUpdateConcurrencyException)
            {
                if (attempt == maximumAttempts)
                {
                    return Fail("concurrent_rematch", "Another player updated the rematch first. Try again.", 409);
                }

                dbContext.ChangeTracker.Clear();
            }
        }

        throw new InvalidOperationException("The rematch retry loop ended unexpectedly.");
    }

    private async Task<GameCommandResult<GameSessionSnapshot>> AcceptRematchCoreAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var session = await LoadSessionAsync(identity.ApplicationKey, sessionId, cancellationToken);
        if (session is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        try
        {
            var now = timeProvider.GetUtcNow();
            var restarted = session.AcceptRematch(identity.MembershipId, now);
            if (!restarted)
            {
                await dbContext.SaveChangesAsync(cancellationToken);
                var waitingSnapshot = await BuildSnapshotAsync(session, identity.MembershipId, cancellationToken);
                await realtime.RematchRequestedAsync(ForRealtime(waitingSnapshot), cancellationToken);
                return GameCommandResult<GameSessionSnapshot>.Success(waitingSnapshot);
            }

            if (session.GameType == "xo")
            {
                var state = await dbContext.XoStates.SingleAsync(x => x.SessionId == session.Id, cancellationToken);
                var moves = await dbContext.XoMoves
                    .Where(x => x.SessionId == session.Id)
                    .OrderBy(x => x.AcceptedVersion)
                    .ToArrayAsync(cancellationToken);
                dbContext.XoMoves.RemoveRange(moves);
                state.Reset(session.Players.Single(x => x.Seat == 0).MembershipId);
            }
            else if (session.GameType == "autobus")
            {
                var state = await dbContext.AutobusStates.SingleAsync(x => x.SessionId == session.Id, cancellationToken);
                var commands = await dbContext.AutobusCommands
                    .Where(x => x.SessionId == session.Id)
                    .ToArrayAsync(cancellationToken);
                dbContext.AutobusCommands.RemoveRange(commands);
                state.ResetForReplay(
                    state.ToRuleset(session.RulesetKey),
                    session.Players.Select(x => x.MembershipId).ToArray(),
                    now);
            }

            await dbContext.SaveChangesAsync(cancellationToken);
            var snapshot = await BuildSnapshotAsync(session, identity.MembershipId, cancellationToken);
            await realtime.RematchAcceptedAsync(ForRealtime(snapshot), cancellationToken);
            await realtime.GameStartedAsync(ForRealtime(snapshot), cancellationToken);
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
        var session = await LoadSessionAsync(identity.ApplicationKey, sessionId, cancellationToken);
        if (session is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

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
            await BuildSnapshotAsync(session, identity.MembershipId, cancellationToken));
    }

    private async Task<GameCommandResult<GameSessionSnapshot>> AutobusCommandCoreAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        string commandId,
        long expectedVersion,
        string kind,
        Func<GameSession, AutobusSessionState, DateTimeOffset, bool> mutate,
        Func<GameSessionSnapshot, CancellationToken, Task> notify,
        CancellationToken cancellationToken,
        bool allowStaleVersion = false)
    {
        if (string.IsNullOrWhiteSpace(commandId))
        {
            return Fail("command_id_required", "A command id is required.", 400);
        }

        var session = await LoadSessionAsync(identity.ApplicationKey, sessionId, cancellationToken);
        if (session is null)
        {
            return Fail("session_not_found", "The game session was not found.", 404);
        }

        if (session.GameType != "autobus")
        {
            return Fail("game_type_invalid", "This command is only available for Autobus Lamma.", 409);
        }

        if (session.Players.All(x => x.MembershipId != identity.MembershipId))
        {
            return Fail("not_participant", "The caller is not a session participant.", 403);
        }

        var state = await dbContext.AutobusStates.SingleAsync(x => x.SessionId == session.Id, cancellationToken);
        if (await dbContext.AutobusCommands.AnyAsync(
                x => x.SessionId == session.Id && x.CommandId == commandId.Trim(),
                cancellationToken))
        {
            return GameCommandResult<GameSessionSnapshot>.Success(await BuildSnapshotAsync(session, identity.MembershipId, cancellationToken));
        }

        if (expectedVersion != state.Version && !allowStaleVersion)
        {
            return Fail("stale_version", "The game changed. Refresh authoritative state.", 409);
        }

        if (session.Status != GameSessionStatus.Started)
        {
            return Fail("game_not_active", "The game is not active.", 409);
        }

        try
        {
            var now = timeProvider.GetUtcNow();
            var completed = mutate(session, state, now);
            dbContext.AutobusCommands.Add(
                new AutobusCommand(
                    Guid.NewGuid(),
                    session.Id,
                    commandId,
                    identity.MembershipId,
                    kind,
                    state.Version,
                    now));
            await dbContext.SaveChangesAsync(cancellationToken);
            var snapshot = await BuildSnapshotAsync(session, identity.MembershipId, cancellationToken);
            await notify(ForRealtime(snapshot), cancellationToken);
            if (completed)
            {
                await realtime.GameCompletedAsync(snapshot, cancellationToken);
            }

            return GameCommandResult<GameSessionSnapshot>.Success(snapshot);
        }
        catch (UnauthorizedAccessException)
        {
            return Fail("not_participant", "The caller is not eligible for this action.", 403);
        }
        catch (InvalidOperationException error)
        {
            logger.LogWarning(
                "Rejected Autobus command {CommandKind} in session {SessionId}: {Reason}",
                kind,
                session.Id,
                error.Message);
            return Fail("autobus_command_invalid", "The Autobus action is not valid for the current state.", 409);
        }
        catch (DbUpdateConcurrencyException)
        {
            return Fail("concurrent_command", "Another action updated the game first. Refresh authoritative state.", 409);
        }
        catch (DbUpdateException)
        {
            return Fail("duplicate_or_concurrent_command", "The command was already applied or superseded.", 409);
        }
    }

    private async Task<(GameSession Session, XoSessionState State, IReadOnlyList<XoMove> Moves)?> LoadXoAsync(
        string applicationKey,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var session = await LoadSessionAsync(applicationKey, sessionId, cancellationToken);
        if (session is null)
        {
            return null;
        }

        if (session.GameType != "xo")
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

    private Task<GameSession?> LoadSessionAsync(
        string applicationKey,
        Guid sessionId,
        CancellationToken cancellationToken) =>
        dbContext.Sessions
            .Include(x => x.Players)
            .SingleOrDefaultAsync(
                x => x.Id == sessionId && x.ApplicationKey == applicationKey,
                cancellationToken);

    private async Task<GameSession?> LoadSessionByCodeAsync(
        string applicationKey,
        string joinCode,
        CancellationToken cancellationToken)
    {
        var sessionId = await dbContext.Sessions
            .Where(x => x.ApplicationKey == applicationKey && x.JoinCode == joinCode)
            .Select(x => (Guid?)x.Id)
            .SingleOrDefaultAsync(cancellationToken);
        return sessionId.HasValue
            ? await LoadSessionAsync(applicationKey, sessionId.Value, cancellationToken)
            : null;
    }

    private async Task<GameSessionSnapshot> BuildSnapshotAsync(
        GameSession session,
        Guid? viewerMembershipId,
        CancellationToken cancellationToken)
    {
        if (session.GameType == "autobus")
        {
            var state = await dbContext.AutobusStates.SingleAsync(x => x.SessionId == session.Id, cancellationToken);
            return BuildAutobusSnapshot(session, state, viewerMembershipId);
        }

        var xoState = await dbContext.XoStates.SingleAsync(x => x.SessionId == session.Id, cancellationToken);
        var moves = await dbContext.XoMoves
            .Where(x => x.SessionId == session.Id)
            .OrderBy(x => x.AcceptedVersion)
            .ToArrayAsync(cancellationToken);
        return BuildXoSnapshot(session, xoState, moves, Guid.Empty);
    }

    private static GameSessionSnapshot BuildXoSnapshot(
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

    private static GameSessionSnapshot BuildAutobusSnapshot(
        GameSession session,
        AutobusSessionState state,
        Guid? viewerMembershipId)
    {
        var orderedPlayers = session.Players.OrderBy(x => x.Seat).ToArray();
        var categories = state.Categories;
        var answers = state.Answers
            .Where(x => x.Round == state.CurrentRound)
            .Where(x => state.Phase == AutobusRoundPhase.Completed ||
                state.Phase == AutobusRoundPhase.Reveal && x.CategoryKey == state.RevealCategoryKey ||
                state.Phase is AutobusRoundPhase.Active or AutobusRoundPhase.Grace &&
                    viewerMembershipId.HasValue && x.PlayerMembershipId == viewerMembershipId.Value)
            .Select(x =>
                new AutobusAnswerSnapshot(
                    x.PlayerMembershipId,
                    x.CategoryKey,
                    x.DisplayAnswer,
                    x.Outcome.ToString().ToLowerInvariant(),
                    x.ReasonCode,
                    x.FriendlyMessageCode,
                    x.CanonicalValue,
                    x.Score,
                    x.Scored,
                    x.Outcome == AutobusAnswerOutcome.NeedsVote,
                    x.Duplicate))
            .ToArray();

        var scores = state.Scores
            .Select(x => new AutobusScoreSnapshot(x.PlayerMembershipId, x.Score))
            .ToArray();
        var votes = state.Votes
            .Where(x => x.Round == state.CurrentRound)
            .Where(x => state.Phase == AutobusRoundPhase.Completed ||
                state.Phase == AutobusRoundPhase.Reveal && x.CategoryKey == state.RevealCategoryKey)
            .Select(x => new AutobusVoteSnapshot(x.AnswerOwnerMembershipId, x.CategoryKey, x.VoterMembershipId, x.Accept))
            .ToArray();

        var orderedScores = scores.OrderByDescending(x => x.Score).ToArray();
        var topScore = orderedScores.FirstOrDefault()?.Score;
        var winnerMembershipId = state.Phase == AutobusRoundPhase.Completed &&
            topScore.HasValue &&
            orderedScores.Count(score => score.Score == topScore.Value) == 1
                ? orderedScores[0].PlayerMembershipId
                : (Guid?)null;
        var matchStatus = state.Phase == AutobusRoundPhase.Completed
            ? winnerMembershipId.HasValue ? "won" : "draw"
            : state.Phase.ToString().ToLowerInvariant();
        return new GameSessionSnapshot(
            session.Id,
            session.JoinCode,
            session.GameType,
            session.Status.ToString().ToLowerInvariant(),
            session.MatchNumber,
            new GameRulesetSnapshot(
                session.RulesetKey,
                0,
                0,
                session.MaximumPlayers,
                state.RoundSeconds,
                true,
                false,
                session.RequiredEntitlement),
            orderedPlayers.Select(player =>
                new GamePlayerSnapshot(
                    player.MembershipId,
                    player.DisplayName,
                    player.Seat,
                    (player.Seat + 1).ToString(CultureInfo.InvariantCulture),
                    player.IsReady,
                    player.IsConnected)).ToArray(),
            [],
            state.Version,
            null,
            winnerMembershipId,
            matchStatus,
            session.RematchRequestedByMembershipId,
            session.LastActivityAtUtc,
            Math.Max(session.LastActivityAtUtc.UtcDateTime.Ticks, state.Version),
            new AutobusSnapshot(
                1,
                state.Difficulty,
                state.RoundCount,
                state.RoundSeconds,
                categories.Select(x => new AutobusCategorySnapshot(x.Key, x.ArabicName, x.EnglishName)).ToArray(),
                state.CurrentRound,
                state.CurrentLetter,
                state.Phase.ToString().ToLowerInvariant(),
                state.RoundStartedAtUtc,
                state.RoundDeadlineAtUtc,
                state.GraceEndsAtUtc,
                state.VoteDeadlineAtUtc,
                state.RevealCategoryKey,
                answers,
                scores,
                votes,
                state.TieMessageCode));
    }

    private static GameSessionSnapshot ForRealtime(GameSessionSnapshot snapshot)
    {
        if (snapshot.Autobus?.Phase is not ("active" or "grace")) return snapshot;
        return snapshot with { Autobus = snapshot.Autobus with { Answers = [] } };
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

    private static bool IsAutobusRequest(CreateGameSessionRequest request) =>
        string.Equals(request.GameType, "autobus", StringComparison.OrdinalIgnoreCase) ||
        request.RulesetKey.StartsWith("autobus", StringComparison.OrdinalIgnoreCase);

    private static GameCommandResult<GameSessionSnapshot> Fail(string code, string message, int statusCode) =>
        GameCommandResult<GameSessionSnapshot>.Failure(code, message, statusCode);

    private Task<GameCommandResult<GameSessionSnapshot>> ExecuteMembershipWriteAsync(
        string applicationKey,
        Guid membershipId,
        Func<Task<GameCommandResult<GameSessionSnapshot>>> operation,
        CancellationToken cancellationToken) =>
        _membershipWriteFence.ExecuteAsync(
            applicationKey,
            membershipId,
            operation,
            () => Fail("account_deleted", "The game membership has been deleted.", 410),
            cancellationToken);
}
