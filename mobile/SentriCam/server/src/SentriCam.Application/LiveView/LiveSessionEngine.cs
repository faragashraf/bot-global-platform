using Microsoft.Extensions.Logging;
using SentriCam.Application.Common;
using SentriCam.Application.Connections;
using SentriCam.Contracts.LiveView;
using SentriCam.Domain.Devices;

namespace SentriCam.Application.LiveView;

public sealed record LiveSessionOwner(string Subject, string ConnectionId);

public sealed class LiveSessionException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

public interface ILiveSessionEngine
{
    LiveViewAvailability GetAvailability(Guid deviceId);
    LiveWallMetricsView GetMetrics();
    Task<LiveSessionView> CreateAsync(CreateLiveSessionRequest request, LiveSessionOwner owner, CancellationToken cancellationToken = default);
    Task<LiveSessionView> SubmitOfferAsync(LiveSessionDescription offer, LiveSessionOwner owner, CancellationToken cancellationToken = default);
    Task SubmitAnswerAsync(Guid deviceId, string deviceConnectionId, LiveSessionDescription answer, CancellationToken cancellationToken = default);
    Task SubmitOperatorCandidateAsync(LiveIceCandidate candidate, LiveSessionOwner owner, CancellationToken cancellationToken = default);
    Task SubmitDeviceCandidateAsync(Guid deviceId, string deviceConnectionId, LiveIceCandidate candidate, CancellationToken cancellationToken = default);
    Task ReportDeviceStateAsync(Guid deviceId, string deviceConnectionId, LiveSessionStatusUpdate update, CancellationToken cancellationToken = default);
    Task ReportStatisticsAsync(Guid? deviceId, string connectionId, LiveSessionStatistics statistics, CancellationToken cancellationToken = default);
    Task SetPreviewVisibilityAsync(Guid sessionId, bool visible, LiveSessionOwner owner, CancellationToken cancellationToken = default);
    Task<LiveSessionView> CloseAsync(Guid sessionId, LiveSessionOwner owner, CancellationToken cancellationToken = default);
    Task CloseForOperatorConnectionAsync(string connectionId, CancellationToken cancellationToken = default);
    Task CloseForDeviceConnectionAsync(Guid deviceId, string connectionId, CancellationToken cancellationToken = default);
    Task RecoverForDeviceConnectionAsync(Guid deviceId, string connectionId, CancellationToken cancellationToken = default);
    bool HasActiveSession(Guid deviceId);
    Task<bool> CloseForDeviceAsync(Guid deviceId, CancellationToken cancellationToken = default);
    Task<int> ExpireAsync(CancellationToken cancellationToken = default);
    void ReportCapabilities(Guid authenticatedDeviceId, string connectionId, LiveDeviceCapabilities capabilities);
}

public sealed class LiveSessionEngine(
    IDeviceConnectionManager connections,
    ILiveViewTransport transport,
    TimeProvider timeProvider,
    ILogger<LiveSessionEngine>? logger = null) : ILiveSessionEngine
{
    private static readonly Action<ILogger, string, Guid, Guid, string, string, string, Exception?> LogLiveSignal =
        LoggerMessage.Define<string, Guid, Guid, string, string, string>(
            LogLevel.Information,
            new EventId(1, "LiveSignal"),
            "event={EventName} liveSessionId={SessionId} deviceId={DeviceId} connectionId={DeviceConnectionId} operatorConnectionId={OperatorConnectionId} state={State}");
    private static readonly Action<ILogger, int, int, int, long, Exception?> LogLiveWallMetrics =
        LoggerMessage.Define<int, int, int, long>(
            LogLevel.Debug,
            new EventId(2, "LiveWallMetrics"),
            "Live Wall metrics viewers={ActiveViewers} tiles={ActiveTiles} publishers={ActivePublishers} estimatedBandwidthBitsPerSecond={EstimatedBandwidthBitsPerSecond}");
    private static readonly Action<ILogger, Guid, Guid, double, Exception?> LogFirstFrame =
        LoggerMessage.Define<Guid, Guid, double>(
            LogLevel.Information,
            new EventId(3, "LiveFirstFrame"),
            "Live first frame session={SessionId} device={DeviceId} durationMilliseconds={DurationMilliseconds}");
    private static readonly Action<ILogger, Guid, Guid, double, Exception?> LogCleanup =
        LoggerMessage.Define<Guid, Guid, double>(
            LogLevel.Debug,
            new EventId(4, "LiveSessionCleanup"),
            "Live cleanup session={SessionId} device={DeviceId} durationMilliseconds={DurationMilliseconds}");
    private static readonly Action<ILogger, Guid, string, string, string?, DateTimeOffset, Exception?> LogReadiness =
        LoggerMessage.Define<Guid, string, string, string?, DateTimeOffset>(
            LogLevel.Debug,
            new EventId(5, "LiveReadiness"),
            "event=live_readiness deviceId={DeviceId} connectionId={ConnectionId} readiness={Readiness} reason={Reason} atUtc={AtUtc}");
    private static readonly TimeSpan NegotiationTimeout = TimeSpan.FromSeconds(45);
    private static readonly TimeSpan ActivityTimeout = TimeSpan.FromSeconds(30);
    private static readonly IReadOnlyList<LiveQualityOption> QualityOptions =
    [
        new("auto", "Auto", false, 1280, 720, 30),
        new("low", "Low", false, 640, 360, 24),
        new(LiveViewProtocol.ImplementedQuality, "Medium", true, 1280, 720, 30),
        new("high", "High", false, 1920, 1080, 30),
    ];

    private readonly object _gate = new();
    private readonly ILogger<LiveSessionEngine> _logger =
        logger ?? Microsoft.Extensions.Logging.Abstractions.NullLogger<LiveSessionEngine>.Instance;
    private readonly Dictionary<Guid, ConnectionCapabilities> _capabilities = [];
    private readonly Dictionary<Guid, ConnectionFailure> _failures = [];
    private readonly Dictionary<Guid, ActiveSession> _sessions = [];

    public LiveViewAvailability GetAvailability(Guid deviceId)
    {
        var connection = connections.GetStatus(DeviceId.From(deviceId));
        ConnectionCapabilities? report;
        ConnectionFailure? failure;
        ActiveSession? active;
        lock (_gate)
        {
            report = _capabilities.GetValueOrDefault(deviceId);
            failure = _failures.GetValueOrDefault(deviceId);
            active = _sessions.Values.FirstOrDefault(session => session.DeviceId == deviceId);
        }

        if (connection?.State == DeviceTransportState.Disconnected || connection is null)
        {
            return Availability(deviceId, LiveReadinessStates.Unavailable, "device_offline", null, null);
        }
        if (connection.State == DeviceTransportState.Recovering)
        {
            return Availability(deviceId, LiveReadinessStates.Recovering, "device_recovering", null, null);
        }
        if (report is null
            || !string.Equals(report.ConnectionId, connection.ConnectionId, StringComparison.Ordinal))
        {
            return Availability(deviceId, LiveReadinessStates.Initializing, "live_capabilities_stale", null, null);
        }
        if (!report.Capabilities.Cameras.Any(camera => camera.Available))
        {
            return Availability(
                deviceId,
                LiveReadinessStates.Unavailable,
                "camera_unavailable",
                report.Capabilities,
                report.ReceivedAtUtc);
        }
        var reportedReadiness = NormalizeReadiness(report.Capabilities.Readiness);
        if (reportedReadiness != LiveReadinessStates.Ready)
        {
            return Availability(
                deviceId,
                reportedReadiness,
                report.Capabilities.UnavailableReason ?? "live_not_ready",
                report.Capabilities,
                report.ReceivedAtUtc);
        }
        if (active is not null)
        {
            var readiness = active.State switch
            {
                LiveSessionStates.Connected => LiveReadinessStates.Live,
                LiveSessionStates.Buffering => LiveReadinessStates.Recovering,
                LiveSessionStates.Failed => LiveReadinessStates.Failed,
                _ => LiveReadinessStates.Starting,
            };
            return Availability(
                deviceId,
                readiness,
                readiness == LiveReadinessStates.Recovering ? active.ErrorCode ?? "live_session_recovering" : "session_conflict",
                report.Capabilities,
                report.ReceivedAtUtc);
        }
        if (failure is not null
            && string.Equals(failure.ConnectionId, connection.ConnectionId, StringComparison.Ordinal))
        {
            return Availability(
                deviceId,
                LiveReadinessStates.Failed,
                failure.Code,
                report.Capabilities,
                failure.FailedAtUtc);
        }

        return Availability(
            deviceId,
            LiveReadinessStates.Ready,
            null,
            report.Capabilities,
            report.ReceivedAtUtc);
    }

    private static LiveViewAvailability Availability(
        Guid deviceId,
        string readiness,
        string? reason,
        LiveDeviceCapabilities? capabilities,
        DateTimeOffset? reportedAtUtc)
    {
        var available = readiness == LiveReadinessStates.Ready;
        return new LiveViewAvailability(
            deviceId,
            available,
            reason,
            capabilities,
            QualityOptions,
            readiness,
            reportedAtUtc);
    }

    public LiveWallMetricsView GetMetrics()
    {
        lock (_gate)
        {
            var sessions = _sessions.Values.ToArray();
            var firstFrameDurations = sessions
                .Where(session => session.FirstFrameAtUtc.HasValue)
                .Select(session => (session.FirstFrameAtUtc!.Value - session.CreatedAtUtc).TotalMilliseconds)
                .ToArray();
            var bandwidth = sessions
                .Where(session => session.EstimatedBandwidthBitsPerSecond.HasValue)
                .Sum(session => session.EstimatedBandwidthBitsPerSecond!.Value);
            return new LiveWallMetricsView(
                timeProvider.GetUtcNow(),
                sessions.Select(session => session.Owner.ConnectionId).Distinct(StringComparer.Ordinal).Count(),
                sessions.Length,
                sessions.Count(session => session.State != LiveSessionStates.Buffering),
                sessions.Sum(session => session.ReconnectCount),
                firstFrameDurations.Length == 0 ? null : firstFrameDurations.Average(),
                sessions.Any(session => session.EstimatedBandwidthBitsPerSecond.HasValue) ? bandwidth : null);
        }
    }

    public async Task<LiveSessionView> CreateAsync(
        CreateLiveSessionRequest request,
        LiveSessionOwner owner,
        CancellationToken cancellationToken = default)
    {
        ValidateOwner(owner);
        if (!string.Equals(request.Quality, LiveViewProtocol.ImplementedQuality, StringComparison.OrdinalIgnoreCase))
        {
            throw new LiveSessionException("quality_not_available", "Medium is the only available Live View quality in this version.");
        }
        var deviceId = DeviceId.From(request.DeviceId);
        var connection = connections.GetStatus(deviceId);
        if (connection?.State != DeviceTransportState.Connected)
        {
            throw new LiveSessionException("device_offline", "The selected camera is not connected to this Hub.");
        }

        ActiveSession active;
        lock (_gate)
        {
            if (!_capabilities.TryGetValue(request.DeviceId, out var report)
                || !string.Equals(report.ConnectionId, connection.ConnectionId, StringComparison.Ordinal))
            {
                throw new LiveSessionException(
                    "live_capabilities_stale",
                    "The selected camera has not confirmed Live readiness on its current connection.");
            }
            if (!report.Capabilities.Cameras.Any(camera => camera.Available))
            {
                throw new LiveSessionException("camera_unavailable", "The selected camera is not ready for Live View.");
            }
            if (NormalizeReadiness(report.Capabilities.Readiness) != LiveReadinessStates.Ready)
            {
                throw new LiveSessionException(
                    report.Capabilities.UnavailableReason ?? "live_not_ready",
                    "The selected camera has not confirmed that its Live publisher is ready.");
            }
            if (_failures.TryGetValue(request.DeviceId, out var previousFailure)
                && string.Equals(previousFailure.ConnectionId, connection.ConnectionId, StringComparison.Ordinal))
            {
                throw new LiveSessionException(previousFailure.Code, "The selected camera must report Live readiness before retrying.");
            }
            if (_sessions.Values.Any(session => session.DeviceId == request.DeviceId))
            {
                throw new LiveSessionException("session_conflict", "This camera already has an active Live View session.");
            }
            var now = timeProvider.GetUtcNow();
            active = new ActiveSession(
                Guid.NewGuid(),
                request.DeviceId,
                connection.ConnectionId,
                owner,
                LiveSessionStates.Connecting,
                LiveViewProtocol.ImplementedQuality,
                now,
                now,
                now.Add(NegotiationTimeout),
                PreviewVisible: true,
                ErrorCode: null,
                DeviceAcknowledged: false,
                ReconnectCount: 0,
                FirstFrameAtUtc: null,
                LastBrowserBytesReceived: null,
                LastBrowserStatisticsAtUtc: null,
                EstimatedBandwidthBitsPerSecond: null);
            _sessions.Add(active.SessionId, active);
        }

        var view = Map(active);
        LogSignal(active, "assignment_created");
        try
        {
            await transport.BeginOnDeviceAsync(
                active.DeviceConnectionId,
                new LiveSessionAssignment(view, active.PreviewVisible, LiveViewProtocol.Version),
                cancellationToken);
            LogSignal(active, "assignment_routed_to_device");
            await transport.SessionChangedAsync(owner.ConnectionId, view, cancellationToken);
            return view;
        }
        catch (OperationCanceledException)
        {
            ClearIfActive(active.SessionId);
            throw;
        }
        catch (Exception)
        {
            ClearIfActive(active.SessionId);
            RememberFailure(active, "device_unavailable");
            try
            {
                await transport.EndOnDeviceAsync(
                    active.DeviceConnectionId,
                    new LiveSessionStatusUpdate(active.SessionId, LiveSessionStates.Failed, "device_unavailable"),
                    CancellationToken.None);
            }
            catch
            {
                // The assignment channel is already unavailable; session ownership is still released.
            }
            throw new LiveSessionException("device_unavailable", "The selected camera could not start Live View.");
        }
    }

    public async Task<LiveSessionView> SubmitOfferAsync(
        LiveSessionDescription offer,
        LiveSessionOwner owner,
        CancellationToken cancellationToken = default)
    {
        ValidateDescription(offer, "offer");
        ActiveSession active;
        lock (_gate)
        {
            active = RequireOwner(offer.SessionId, owner);
            active = Touch(active, LiveSessionStates.Negotiating);
            _sessions[active.SessionId] = active;
        }
        LogSignal(active, "offer_received_from_browser");
        await transport.SendOfferToDeviceAsync(active.DeviceConnectionId, offer, cancellationToken);
        LogSignal(active, "offer_routed_to_device");
        var view = Map(active);
        await transport.SessionChangedAsync(owner.ConnectionId, view, cancellationToken);
        return view;
    }

    public async Task SubmitAnswerAsync(
        Guid deviceId,
        string deviceConnectionId,
        LiveSessionDescription answer,
        CancellationToken cancellationToken = default)
    {
        ValidateDescription(answer, "answer");
        ActiveSession active;
        lock (_gate)
        {
            active = RequireDevice(answer.SessionId, deviceId, deviceConnectionId);
            active = Touch(active, LiveSessionStates.Negotiating);
            _sessions[active.SessionId] = active;
        }
        LogSignal(active, "answer_received_from_device");
        await transport.SendAnswerToOperatorAsync(active.Owner.ConnectionId, answer, cancellationToken);
        LogSignal(active, "answer_routed_to_browser");
    }

    public async Task SubmitOperatorCandidateAsync(
        LiveIceCandidate candidate,
        LiveSessionOwner owner,
        CancellationToken cancellationToken = default)
    {
        ValidateCandidate(candidate);
        ActiveSession active;
        lock (_gate)
        {
            active = Touch(RequireOwner(candidate.SessionId, owner));
            _sessions[active.SessionId] = active;
        }
        LogSignal(active, "candidate_received_from_browser");
        await transport.SendCandidateToDeviceAsync(active.DeviceConnectionId, candidate, cancellationToken);
        LogSignal(active, "candidate_routed_to_device");
    }

    public async Task SubmitDeviceCandidateAsync(
        Guid deviceId,
        string deviceConnectionId,
        LiveIceCandidate candidate,
        CancellationToken cancellationToken = default)
    {
        ValidateCandidate(candidate);
        ActiveSession active;
        lock (_gate)
        {
            active = Touch(RequireDevice(candidate.SessionId, deviceId, deviceConnectionId));
            _sessions[active.SessionId] = active;
        }
        LogSignal(active, "candidate_received_from_device");
        await transport.SendCandidateToOperatorAsync(active.Owner.ConnectionId, candidate, cancellationToken);
        LogSignal(active, "candidate_routed_to_browser");
    }

    public async Task ReportDeviceStateAsync(
        Guid deviceId,
        string deviceConnectionId,
        LiveSessionStatusUpdate update,
        CancellationToken cancellationToken = default)
    {
        if (!AllowedReportedStates.Contains(update.State))
        {
            throw new LiveSessionException("invalid_state", "The reported Live View state is invalid.");
        }
        ActiveSession active;
        lock (_gate)
        {
            active = RequireDevice(update.SessionId, deviceId, deviceConnectionId);
            active = Touch(active, update.State, update.ErrorCode) with { DeviceAcknowledged = true };
            _sessions[active.SessionId] = active;
            if (update.State == LiveSessionStates.Failed)
            {
                _failures[deviceId] = new ConnectionFailure(
                    deviceConnectionId,
                    update.ErrorCode ?? "live_start_failed",
                    timeProvider.GetUtcNow());
            }
        }
        LogSignal(active, $"device_state_{update.State}");
        await transport.SessionChangedAsync(active.Owner.ConnectionId, Map(active), cancellationToken);
        if (update.State is LiveSessionStates.Failed or LiveSessionStates.Disconnected)
        {
            await CloseInternalAsync(active, update.State, update.ErrorCode, cancellationToken);
        }
    }

    public async Task ReportStatisticsAsync(
        Guid? deviceId,
        string connectionId,
        LiveSessionStatistics statistics,
        CancellationToken cancellationToken = default)
    {
        ValidateStatistics(statistics);
        var expectedSource = deviceId.HasValue ? "device" : "browser";
        if (!string.Equals(statistics.Source, expectedSource, StringComparison.Ordinal))
        {
            throw new LiveSessionException("invalid_statistics", "The Live View statistics source does not match the authenticated peer.");
        }
        ActiveSession active;
        lock (_gate)
        {
            active = deviceId.HasValue
                ? RequireDevice(statistics.SessionId, deviceId.Value, connectionId)
                : RequireOwnerConnection(statistics.SessionId, connectionId);
            active = deviceId.HasValue ? Touch(active) : TouchBrowserStatistics(active, statistics);
            _sessions[active.SessionId] = active;
        }
        if (deviceId.HasValue)
        {
            await transport.StatisticsUpdatedAsync(active.Owner.ConnectionId, statistics, cancellationToken);
        }
        else
        {
            await transport.BrowserStatisticsUpdatedAsync(active.DeviceConnectionId, statistics, cancellationToken);
        }
    }

    public async Task SetPreviewVisibilityAsync(
        Guid sessionId,
        bool visible,
        LiveSessionOwner owner,
        CancellationToken cancellationToken = default)
    {
        ActiveSession active;
        lock (_gate)
        {
            active = RequireOwner(sessionId, owner) with { PreviewVisible = visible };
            active = Touch(active);
            _sessions[active.SessionId] = active;
        }
        await transport.SetPreviewVisibilityAsync(
            active.DeviceConnectionId,
            new LivePreviewVisibility(sessionId, visible),
            cancellationToken);
    }

    public Task<LiveSessionView> CloseAsync(
        Guid sessionId,
        LiveSessionOwner owner,
        CancellationToken cancellationToken = default)
    {
        ActiveSession active;
        lock (_gate)
        {
            active = RequireOwner(sessionId, owner);
        }
        return CloseInternalAsync(active, LiveSessionStates.Closed, null, cancellationToken);
    }

    public async Task CloseForOperatorConnectionAsync(
        string connectionId,
        CancellationToken cancellationToken = default)
    {
        ActiveSession[] active;
        lock (_gate)
        {
            active = _sessions.Values
                .Where(session => session.Owner.ConnectionId == connectionId)
                .ToArray();
        }
        foreach (var session in active)
        {
            await CloseInternalAsync(session, LiveSessionStates.Disconnected, "operator_disconnected", cancellationToken);
        }
    }

    public async Task CloseForDeviceConnectionAsync(
        Guid deviceId,
        string connectionId,
        CancellationToken cancellationToken = default)
    {
        ActiveSession[] active;
        lock (_gate)
        {
            if (_capabilities.TryGetValue(deviceId, out var report)
                && string.Equals(report.ConnectionId, connectionId, StringComparison.Ordinal))
            {
                _capabilities.Remove(deviceId);
            }
            if (_failures.TryGetValue(deviceId, out var failure)
                && string.Equals(failure.ConnectionId, connectionId, StringComparison.Ordinal))
            {
                _failures.Remove(deviceId);
            }
            active = _sessions.Values
                .Where(session => session.DeviceId == deviceId
                    && session.DeviceConnectionId == connectionId)
                .ToArray();
        }
        foreach (var session in active)
        {
            ActiveSession recovering;
            lock (_gate)
            {
                var now = timeProvider.GetUtcNow();
                recovering = session with
                {
                    State = LiveSessionStates.Buffering,
                    ErrorCode = "realtime_reconnecting",
                    DeviceAcknowledged = false,
                    LastActivityAtUtc = now,
                    ExpiresAtUtc = now.Add(NegotiationTimeout),
                    ReconnectCount = session.ReconnectCount + 1,
                };
                if (!_sessions.ContainsKey(session.SessionId)) continue;
                _sessions[recovering.SessionId] = recovering;
            }
            LogSignal(recovering, "device_connection_lost_recovering");
            await transport.SessionChangedAsync(
                recovering.Owner.ConnectionId,
                Map(recovering),
                cancellationToken);
        }
    }

    public async Task RecoverForDeviceConnectionAsync(
        Guid deviceId,
        string connectionId,
        CancellationToken cancellationToken = default)
    {
        ActiveSession[] recovering;
        lock (_gate)
        {
            var now = timeProvider.GetUtcNow();
            recovering = _sessions.Values
                .Where(session => session.DeviceId == deviceId
                    && (session.State == LiveSessionStates.Buffering
                        || !string.Equals(
                            session.DeviceConnectionId,
                            connectionId,
                            StringComparison.Ordinal)))
                .Select(session => session with
                {
                    DeviceConnectionId = connectionId,
                    State = LiveSessionStates.Connecting,
                    ErrorCode = "device_reconnected",
                    DeviceAcknowledged = false,
                    LastActivityAtUtc = now,
                    ExpiresAtUtc = now.Add(NegotiationTimeout),
                })
                .ToArray();
            foreach (var session in recovering) _sessions[session.SessionId] = session;
        }
        foreach (var session in recovering)
        {
            LogSignal(session, "device_connection_recovered");
            try
            {
                await transport.BeginOnDeviceAsync(
                    connectionId,
                    new LiveSessionAssignment(Map(session), session.PreviewVisible, LiveViewProtocol.Version),
                    cancellationToken);
                await transport.SessionChangedAsync(
                    session.Owner.ConnectionId,
                    Map(session),
                    cancellationToken);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch
            {
                await CloseInternalAsync(
                    session,
                    LiveSessionStates.Failed,
                    "live_recovery_failed",
                    CancellationToken.None);
            }
        }
    }

    public bool HasActiveSession(Guid deviceId)
    {
        lock (_gate)
        {
            return _sessions.Values.Any(session => session.DeviceId == deviceId);
        }
    }

    public async Task<bool> CloseForDeviceAsync(
        Guid deviceId,
        CancellationToken cancellationToken = default)
    {
        ActiveSession? active;
        lock (_gate)
        {
            _capabilities.Remove(deviceId);
            _failures.Remove(deviceId);
            active = _sessions.Values.FirstOrDefault(session => session.DeviceId == deviceId);
        }
        if (active is null)
        {
            return false;
        }

        await CloseInternalAsync(active, LiveSessionStates.Closed, "device_removed", cancellationToken);
        return true;
    }

    public async Task<int> ExpireAsync(CancellationToken cancellationToken = default)
    {
        ActiveSession[] expired;
        lock (_gate)
        {
            var now = timeProvider.GetUtcNow();
            expired = _sessions.Values.Where(session => session.ExpiresAtUtc <= now).ToArray();
        }
        foreach (var session in expired)
        {
            await CloseInternalAsync(session, LiveSessionStates.Failed, "session_timeout", cancellationToken);
        }
        return expired.Length;
    }

    public void ReportCapabilities(
        Guid authenticatedDeviceId,
        string connectionId,
        LiveDeviceCapabilities capabilities)
    {
        ArgumentNullException.ThrowIfNull(capabilities);
        ArgumentException.ThrowIfNullOrWhiteSpace(connectionId);
        if (capabilities.DeviceId != authenticatedDeviceId)
        {
            throw new AccessDeniedException("Camera capabilities do not belong to the authenticated device.");
        }
        if (capabilities.Cameras.Count > 2
            || capabilities.Cameras.Any(camera => camera.Resolutions.Count > 12 || camera.FrameRates.Count > 12))
        {
            throw new LiveSessionException("invalid_capabilities", "The camera capability report is invalid.");
        }
        var current = connections.GetStatus(DeviceId.From(authenticatedDeviceId));
        if (current?.State != DeviceTransportState.Connected
            || !string.Equals(current.ConnectionId, connectionId, StringComparison.Ordinal))
        {
            throw new LiveSessionException(
                "stale_device_connection",
                "Live capability reports are accepted only from the current device connection.");
        }
        var receivedAtUtc = timeProvider.GetUtcNow();
        lock (_gate)
        {
            _capabilities[authenticatedDeviceId] = new ConnectionCapabilities(
                connectionId,
                capabilities,
                receivedAtUtc);
            if (NormalizeReadiness(capabilities.Readiness) == LiveReadinessStates.Ready)
            {
                _failures.Remove(authenticatedDeviceId);
            }
        }
        LogReadiness(
            _logger,
            authenticatedDeviceId,
            connectionId,
            NormalizeReadiness(capabilities.Readiness),
            capabilities.UnavailableReason,
            receivedAtUtc,
            null);
    }

    private void LogSignal(ActiveSession session, string eventName)
    {
        LogLiveSignal(
            _logger,
            eventName,
            session.SessionId,
            session.DeviceId,
            session.DeviceConnectionId,
            session.Owner.ConnectionId,
            session.State,
            null);
        var metrics = GetMetrics();
        LogLiveWallMetrics(
            _logger,
            metrics.ActiveViewerCount,
            metrics.ActiveTileSessions,
            metrics.ActivePublishers,
            metrics.AggregateEstimatedBandwidthBitsPerSecond ?? 0,
            null);
    }

    private void RememberFailure(ActiveSession session, string code)
    {
        lock (_gate)
        {
            _failures[session.DeviceId] = new ConnectionFailure(
                session.DeviceConnectionId,
                code,
                timeProvider.GetUtcNow());
        }
    }

    private static string NormalizeReadiness(string? readiness) => readiness switch
    {
        LiveReadinessStates.Unavailable => LiveReadinessStates.Unavailable,
        LiveReadinessStates.Initializing => LiveReadinessStates.Initializing,
        LiveReadinessStates.Ready => LiveReadinessStates.Ready,
        LiveReadinessStates.Starting => LiveReadinessStates.Starting,
        LiveReadinessStates.Live => LiveReadinessStates.Live,
        LiveReadinessStates.Recovering => LiveReadinessStates.Recovering,
        LiveReadinessStates.Failed => LiveReadinessStates.Failed,
        _ => LiveReadinessStates.Initializing,
    };

    private async Task<LiveSessionView> CloseInternalAsync(
        ActiveSession active,
        string state,
        string? errorCode,
        CancellationToken cancellationToken)
    {
        var cleanupStarted = timeProvider.GetTimestamp();
        ActiveSession closed;
        lock (_gate)
        {
            if (!_sessions.ContainsKey(active.SessionId))
            {
                return Map(active with { State = state, ErrorCode = errorCode });
            }
            var now = timeProvider.GetUtcNow();
            closed = active with
            {
                State = state,
                ErrorCode = errorCode,
                LastActivityAtUtc = now,
                ExpiresAtUtc = now,
            };
            _sessions.Remove(active.SessionId);
            if (state == LiveSessionStates.Failed)
            {
                _failures[active.DeviceId] = new ConnectionFailure(
                    active.DeviceConnectionId,
                    errorCode ?? "live_start_failed",
                    now);
            }
        }
        var update = new LiveSessionStatusUpdate(closed.SessionId, state, errorCode);
        LogSignal(closed, $"session_{state}");
        await transport.EndOnDeviceAsync(closed.DeviceConnectionId, update, cancellationToken);
        var view = Map(closed);
        await transport.SessionChangedAsync(closed.Owner.ConnectionId, view, cancellationToken);
        LogCleanup(
            _logger,
            closed.SessionId,
            closed.DeviceId,
            timeProvider.GetElapsedTime(cleanupStarted).TotalMilliseconds,
            null);
        return view;
    }

    private ActiveSession RequireOwner(Guid sessionId, LiveSessionOwner owner)
    {
        ValidateOwner(owner);
        var active = RequireActive(sessionId);
        if (active.Owner != owner)
        {
            throw new LiveSessionException("session_not_owned", "This Live View session belongs to another operator connection.");
        }
        return active;
    }

    private ActiveSession RequireOwnerConnection(Guid sessionId, string connectionId)
    {
        var active = RequireActive(sessionId);
        if (!string.Equals(active.Owner.ConnectionId, connectionId, StringComparison.Ordinal))
        {
            throw new LiveSessionException("session_not_owned", "This Live View session belongs to another operator connection.");
        }
        return active;
    }

    private ActiveSession RequireDevice(Guid sessionId, Guid deviceId, string connectionId)
    {
        var active = RequireActive(sessionId);
        if (active.DeviceId != deviceId
            || !string.Equals(active.DeviceConnectionId, connectionId, StringComparison.Ordinal))
        {
            throw new LiveSessionException("device_not_owned", "This Live View session does not belong to the authenticated device connection.");
        }
        return active;
    }

    private ActiveSession RequireActive(Guid sessionId) =>
        _sessions.TryGetValue(sessionId, out var active)
            ? active
            : throw new LiveSessionException("session_not_found", "The Live View session is no longer active.");

    private void ClearIfActive(Guid sessionId)
    {
        lock (_gate)
        {
            _sessions.Remove(sessionId);
        }
    }

    private ActiveSession Touch(ActiveSession active, string? state = null, string? errorCode = null)
    {
        var now = timeProvider.GetUtcNow();
        return active with
        {
            State = state ?? active.State,
            ErrorCode = errorCode,
            LastActivityAtUtc = now,
            ExpiresAtUtc = now.Add(state == LiveSessionStates.Negotiating ? NegotiationTimeout : ActivityTimeout),
        };
    }

    private ActiveSession TouchBrowserStatistics(ActiveSession active, LiveSessionStatistics statistics)
    {
        var touched = Touch(active);
        var firstFrame = active.FirstFrameAtUtc;
        if (firstFrame is null
            && ((statistics.FramesPerSecond ?? 0) > 0 || (statistics.BytesReceived ?? 0) > 0))
        {
            firstFrame = timeProvider.GetUtcNow();
            LogFirstFrame(
                _logger,
                active.SessionId,
                active.DeviceId,
                (firstFrame.Value - active.CreatedAtUtc).TotalMilliseconds,
                null);
        }

        long? estimatedBandwidth = active.EstimatedBandwidthBitsPerSecond;
        if (statistics.BytesReceived.HasValue
            && active.LastBrowserBytesReceived.HasValue
            && active.LastBrowserStatisticsAtUtc.HasValue)
        {
            var elapsed = statistics.SampledAtUtc - active.LastBrowserStatisticsAtUtc.Value;
            var bytes = statistics.BytesReceived.Value - active.LastBrowserBytesReceived.Value;
            if (elapsed > TimeSpan.Zero && bytes >= 0)
            {
                estimatedBandwidth = (long)Math.Round(bytes * 8 / elapsed.TotalSeconds);
            }
        }

        return touched with
        {
            FirstFrameAtUtc = firstFrame,
            LastBrowserBytesReceived = statistics.BytesReceived,
            LastBrowserStatisticsAtUtc = statistics.SampledAtUtc,
            EstimatedBandwidthBitsPerSecond = estimatedBandwidth,
        };
    }

    private static LiveSessionView Map(ActiveSession active) => new(
        active.SessionId,
        active.DeviceId,
        active.State,
        active.Quality,
        active.CreatedAtUtc,
        active.LastActivityAtUtc,
        active.ExpiresAtUtc,
        active.ErrorCode,
        active.DeviceAcknowledged);

    private static void ValidateOwner(LiveSessionOwner owner)
    {
        ArgumentNullException.ThrowIfNull(owner);
        if (string.IsNullOrWhiteSpace(owner.Subject) || string.IsNullOrWhiteSpace(owner.ConnectionId))
        {
            throw new LiveSessionException("operator_identity_missing", "The authenticated operator identity is unavailable.");
        }
    }

    private static void ValidateDescription(LiveSessionDescription description, string expectedType)
    {
        ArgumentNullException.ThrowIfNull(description);
        if (!string.Equals(description.Type, expectedType, StringComparison.OrdinalIgnoreCase)
            || string.IsNullOrWhiteSpace(description.Sdp)
            || description.Sdp.Length > 49_152)
        {
            throw new LiveSessionException("invalid_description", "The WebRTC session description is invalid.");
        }
    }

    private static void ValidateCandidate(LiveIceCandidate candidate)
    {
        ArgumentNullException.ThrowIfNull(candidate);
        if (string.IsNullOrWhiteSpace(candidate.Candidate) || candidate.Candidate.Length > 8_192)
        {
            throw new LiveSessionException("invalid_candidate", "The WebRTC ICE candidate is invalid.");
        }
    }

    private static void ValidateStatistics(LiveSessionStatistics statistics)
    {
        ArgumentNullException.ThrowIfNull(statistics);
        if (statistics.Source is not ("browser" or "device"))
        {
            throw new LiveSessionException("invalid_statistics", "The Live View statistics source is invalid.");
        }
    }

    private static readonly HashSet<string> AllowedReportedStates = new(StringComparer.Ordinal)
    {
        LiveSessionStates.Connecting,
        LiveSessionStates.Negotiating,
        LiveSessionStates.Connected,
        LiveSessionStates.Buffering,
        LiveSessionStates.Disconnected,
        LiveSessionStates.Failed,
    };

    private sealed record ActiveSession(
        Guid SessionId,
        Guid DeviceId,
        string DeviceConnectionId,
        LiveSessionOwner Owner,
        string State,
        string Quality,
        DateTimeOffset CreatedAtUtc,
        DateTimeOffset LastActivityAtUtc,
        DateTimeOffset ExpiresAtUtc,
        bool PreviewVisible,
        string? ErrorCode,
        bool DeviceAcknowledged,
        int ReconnectCount,
        DateTimeOffset? FirstFrameAtUtc,
        long? LastBrowserBytesReceived,
        DateTimeOffset? LastBrowserStatisticsAtUtc,
        long? EstimatedBandwidthBitsPerSecond);

    private sealed record ConnectionCapabilities(
        string ConnectionId,
        LiveDeviceCapabilities Capabilities,
        DateTimeOffset ReceivedAtUtc);

    private sealed record ConnectionFailure(
        string ConnectionId,
        string Code,
        DateTimeOffset FailedAtUtc);
}
