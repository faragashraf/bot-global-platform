using SentriCam.Contracts.LiveView;

namespace SentriCam.Application.LiveView;

public interface ILiveViewTransport
{
    Task BeginOnDeviceAsync(
        string deviceConnectionId,
        LiveSessionAssignment assignment,
        CancellationToken cancellationToken = default);

    Task SendOfferToDeviceAsync(
        string deviceConnectionId,
        LiveSessionDescription offer,
        CancellationToken cancellationToken = default);

    Task SendAnswerToOperatorAsync(
        string operatorConnectionId,
        LiveSessionDescription answer,
        CancellationToken cancellationToken = default);

    Task SendCandidateToDeviceAsync(
        string deviceConnectionId,
        LiveIceCandidate candidate,
        CancellationToken cancellationToken = default);

    Task SendCandidateToOperatorAsync(
        string operatorConnectionId,
        LiveIceCandidate candidate,
        CancellationToken cancellationToken = default);

    Task SetPreviewVisibilityAsync(
        string deviceConnectionId,
        LivePreviewVisibility visibility,
        CancellationToken cancellationToken = default);

    Task EndOnDeviceAsync(
        string deviceConnectionId,
        LiveSessionStatusUpdate update,
        CancellationToken cancellationToken = default);

    Task SessionChangedAsync(
        string operatorConnectionId,
        LiveSessionView session,
        CancellationToken cancellationToken = default);

    Task StatisticsUpdatedAsync(
        string operatorConnectionId,
        LiveSessionStatistics statistics,
        CancellationToken cancellationToken = default);

    Task BrowserStatisticsUpdatedAsync(
        string deviceConnectionId,
        LiveSessionStatistics statistics,
        CancellationToken cancellationToken = default);
}

public sealed class NullLiveViewTransport : ILiveViewTransport
{
    public Task BeginOnDeviceAsync(string deviceConnectionId, LiveSessionAssignment assignment, CancellationToken cancellationToken = default) => Task.CompletedTask;
    public Task SendOfferToDeviceAsync(string deviceConnectionId, LiveSessionDescription offer, CancellationToken cancellationToken = default) => Task.CompletedTask;
    public Task SendAnswerToOperatorAsync(string operatorConnectionId, LiveSessionDescription answer, CancellationToken cancellationToken = default) => Task.CompletedTask;
    public Task SendCandidateToDeviceAsync(string deviceConnectionId, LiveIceCandidate candidate, CancellationToken cancellationToken = default) => Task.CompletedTask;
    public Task SendCandidateToOperatorAsync(string operatorConnectionId, LiveIceCandidate candidate, CancellationToken cancellationToken = default) => Task.CompletedTask;
    public Task SetPreviewVisibilityAsync(string deviceConnectionId, LivePreviewVisibility visibility, CancellationToken cancellationToken = default) => Task.CompletedTask;
    public Task EndOnDeviceAsync(string deviceConnectionId, LiveSessionStatusUpdate update, CancellationToken cancellationToken = default) => Task.CompletedTask;
    public Task SessionChangedAsync(string operatorConnectionId, LiveSessionView session, CancellationToken cancellationToken = default) => Task.CompletedTask;
    public Task StatisticsUpdatedAsync(string operatorConnectionId, LiveSessionStatistics statistics, CancellationToken cancellationToken = default) => Task.CompletedTask;
    public Task BrowserStatisticsUpdatedAsync(string deviceConnectionId, LiveSessionStatistics statistics, CancellationToken cancellationToken = default) => Task.CompletedTask;
}
