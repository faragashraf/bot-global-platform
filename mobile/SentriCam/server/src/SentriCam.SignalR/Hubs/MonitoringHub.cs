using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.SignalR;
using SentriCam.Application.LiveView;
using SentriCam.Contracts.LiveView;

namespace SentriCam.SignalR.Hubs;

[Authorize(Policy = AuthorizationPolicies.DeviceControl)]
public sealed class MonitoringHub(ILiveSessionEngine liveSessions) : Hub<IMonitoringHubClient>
{
    public LiveViewAvailability GetLiveAvailability(Guid deviceId) =>
        liveSessions.GetAvailability(deviceId);

    public LiveWallMetricsView GetLiveWallMetrics() => liveSessions.GetMetrics();

    public Task<LiveSessionView> CreateLiveSession(CreateLiveSessionRequest request) =>
        Execute(() => liveSessions.CreateAsync(request, RequireOwner(), Context.ConnectionAborted));

    public Task<LiveSessionView> SendLiveOffer(LiveSessionDescription offer) =>
        Execute(() => liveSessions.SubmitOfferAsync(offer, RequireOwner(), Context.ConnectionAborted));

    public Task SendLiveIceCandidate(LiveIceCandidate candidate) =>
        Execute(() => liveSessions.SubmitOperatorCandidateAsync(candidate, RequireOwner(), Context.ConnectionAborted));

    public Task ReportLiveStatistics(LiveSessionStatistics statistics) =>
        Execute(() => liveSessions.ReportStatisticsAsync(null, Context.ConnectionId, statistics, Context.ConnectionAborted));

    public Task SetLivePreviewVisibility(LivePreviewVisibility visibility) =>
        Execute(() => liveSessions.SetPreviewVisibilityAsync(
            visibility.SessionId,
            visibility.Visible,
            RequireOwner(),
            Context.ConnectionAborted));

    public Task<LiveSessionView> CloseLiveSession(Guid sessionId) =>
        Execute(() => liveSessions.CloseAsync(sessionId, RequireOwner(), Context.ConnectionAborted));

    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        await liveSessions.CloseForOperatorConnectionAsync(Context.ConnectionId, CancellationToken.None);
        await base.OnDisconnectedAsync(exception);
    }

    private LiveSessionOwner RequireOwner()
    {
        var subject = Context.User?.FindFirst("sub")?.Value;
        if (string.IsNullOrWhiteSpace(subject))
        {
            throw new HubException("operator_identity_missing:The authenticated operator identity is unavailable.");
        }
        return new LiveSessionOwner(subject, Context.ConnectionId);
    }

    private static async Task Execute(Func<Task> action)
    {
        try
        {
            await action();
        }
        catch (LiveSessionException exception)
        {
            throw new HubException($"{exception.Code}:{exception.Message}");
        }
    }

    private static async Task<T> Execute<T>(Func<Task<T>> action)
    {
        try
        {
            return await action();
        }
        catch (LiveSessionException exception)
        {
            throw new HubException($"{exception.Code}:{exception.Message}");
        }
    }
}
