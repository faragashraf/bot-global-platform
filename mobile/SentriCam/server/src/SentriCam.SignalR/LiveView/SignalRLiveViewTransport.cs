using Microsoft.AspNetCore.SignalR;
using SentriCam.Application.LiveView;
using SentriCam.Contracts.LiveView;
using SentriCam.Contracts.SignalR;
using SentriCam.SignalR.Hubs;

namespace SentriCam.SignalR.LiveView;

public sealed class SignalRLiveViewTransport(
    IHubContext<DeviceHub, IDeviceHubClient> deviceHub,
    IHubContext<MonitoringHub, IMonitoringHubClient> monitoringHub) : ILiveViewTransport
{
    public Task BeginOnDeviceAsync(
        string deviceConnectionId,
        LiveSessionAssignment assignment,
        CancellationToken cancellationToken = default) =>
        deviceHub.Clients.Client(deviceConnectionId).BeginLiveSession(assignment);

    public Task SendOfferToDeviceAsync(
        string deviceConnectionId,
        LiveSessionDescription offer,
        CancellationToken cancellationToken = default) =>
        deviceHub.Clients.Client(deviceConnectionId).ReceiveLiveOffer(offer);

    public Task SendAnswerToOperatorAsync(
        string operatorConnectionId,
        LiveSessionDescription answer,
        CancellationToken cancellationToken = default) =>
        monitoringHub.Clients.Client(operatorConnectionId).ReceiveLiveAnswer(answer);

    public Task SendCandidateToDeviceAsync(
        string deviceConnectionId,
        LiveIceCandidate candidate,
        CancellationToken cancellationToken = default) =>
        deviceHub.Clients.Client(deviceConnectionId).ReceiveLiveIceCandidate(candidate);

    public Task SendCandidateToOperatorAsync(
        string operatorConnectionId,
        LiveIceCandidate candidate,
        CancellationToken cancellationToken = default) =>
        monitoringHub.Clients.Client(operatorConnectionId).ReceiveLiveIceCandidate(candidate);

    public Task SetPreviewVisibilityAsync(
        string deviceConnectionId,
        LivePreviewVisibility visibility,
        CancellationToken cancellationToken = default) =>
        deviceHub.Clients.Client(deviceConnectionId).SetLivePreviewVisibility(visibility);

    public Task EndOnDeviceAsync(
        string deviceConnectionId,
        LiveSessionStatusUpdate update,
        CancellationToken cancellationToken = default) =>
        deviceHub.Clients.Client(deviceConnectionId).EndLiveSession(update);

    public Task SessionChangedAsync(
        string operatorConnectionId,
        LiveSessionView session,
        CancellationToken cancellationToken = default) =>
        monitoringHub.Clients.Client(operatorConnectionId).LiveSessionChanged(session);

    public Task StatisticsUpdatedAsync(
        string operatorConnectionId,
        LiveSessionStatistics statistics,
        CancellationToken cancellationToken = default) =>
        monitoringHub.Clients.Client(operatorConnectionId).LiveStatisticsUpdated(statistics);

    public Task BrowserStatisticsUpdatedAsync(
        string deviceConnectionId,
        LiveSessionStatistics statistics,
        CancellationToken cancellationToken = default) =>
        deviceHub.Clients.Client(deviceConnectionId).BrowserLiveStatistics(statistics);
}
