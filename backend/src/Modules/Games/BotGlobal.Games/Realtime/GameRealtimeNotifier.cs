using BotGlobal.Games.Application.Sessions;
using Microsoft.AspNetCore.SignalR;

namespace BotGlobal.Games.Realtime;

internal sealed class GameRealtimeNotifier(IHubContext<GamesHub> hubContext) : IGameRealtimeNotifier
{
    public Task SessionCreatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) =>
        SendAsync(snapshot, "SessionCreated", cancellationToken);

    public Task PlayerJoinedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) =>
        SendAsync(snapshot, "PlayerJoined", cancellationToken);

    public Task PlayerReadyAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) =>
        SendAsync(snapshot, "PlayerReady", cancellationToken);

    public Task GameStartedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) =>
        SendAsync(snapshot, "GameStarted", cancellationToken);

    public Task StateUpdatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) =>
        SendAsync(snapshot, "GameStateUpdated", cancellationToken);

    public Task MoveAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) =>
        SendAsync(snapshot, "MoveAccepted", cancellationToken);

    public Task PlayerConnectionChangedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) =>
        SendAsync(snapshot, "PlayerConnectionChanged", cancellationToken);

    public Task GameCompletedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) =>
        SendAsync(snapshot, "GameCompleted", cancellationToken);

    public Task RematchRequestedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) =>
        SendAsync(snapshot, "RematchRequested", cancellationToken);

    public Task RematchAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) =>
        SendAsync(snapshot, "RematchAccepted", cancellationToken);

    private Task SendAsync(
        GameSessionSnapshot snapshot,
        string eventName,
        CancellationToken cancellationToken) =>
        hubContext.Clients.Group(GamesHub.GroupName(snapshot.SessionId))
            .SendAsync(eventName, snapshot, cancellationToken);
}
