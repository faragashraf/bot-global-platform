namespace SentriCam.Contracts.LiveView;

public static class LiveViewProtocol
{
    public const string Version = "1";
    public const string ImplementedQuality = "medium";
}

public static class LiveSessionStates
{
    public const string Connecting = "connecting";
    public const string Negotiating = "negotiating";
    public const string Connected = "connected";
    public const string Buffering = "buffering";
    public const string Disconnected = "disconnected";
    public const string Failed = "failed";
    public const string Closed = "closed";
}

public static class LiveReadinessStates
{
    public const string Unavailable = "unavailable";
    public const string Initializing = "initializing";
    public const string Ready = "ready";
    public const string Starting = "starting";
    public const string Live = "live";
    public const string Recovering = "recovering";
    public const string Failed = "failed";
}

public sealed record CreateLiveSessionRequest(Guid DeviceId, string Quality);

public sealed record LiveQualityOption(
    string Id,
    string Label,
    bool Available,
    int Width,
    int Height,
    int FramesPerSecond);

public sealed record LiveCameraCapability(
    string Lens,
    bool Available,
    bool Torch,
    bool Zoom,
    IReadOnlyList<string> Resolutions,
    IReadOnlyList<int> FrameRates);

public sealed record LiveDeviceCapabilities(
    Guid DeviceId,
    IReadOnlyList<LiveCameraCapability> Cameras,
    bool PreviewVisibilitySupported,
    DateTimeOffset ReportedAtUtc,
    string Readiness = LiveReadinessStates.Ready,
    string? UnavailableReason = null);

public sealed record LiveViewAvailability(
    Guid DeviceId,
    bool Available,
    string? UnavailableReason,
    LiveDeviceCapabilities? Capabilities,
    IReadOnlyList<LiveQualityOption> QualityOptions,
    string Readiness = LiveReadinessStates.Unavailable,
    DateTimeOffset? ReadinessReportedAtUtc = null);

public sealed record LiveSessionView(
    Guid SessionId,
    Guid DeviceId,
    string State,
    string Quality,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset LastActivityAtUtc,
    DateTimeOffset ExpiresAtUtc,
    string? ErrorCode,
    bool DeviceAcknowledged = false);

public sealed record LiveSessionAssignment(
    LiveSessionView Session,
    bool PreviewVisible,
    string ProtocolVersion);

public sealed record LiveSessionDescription(
    Guid SessionId,
    string Type,
    string Sdp);

public sealed record LiveIceCandidate(
    Guid SessionId,
    string Candidate,
    string? SdpMid,
    int? SdpMLineIndex,
    string? UsernameFragment);

public sealed record LiveSessionStatusUpdate(
    Guid SessionId,
    string State,
    string? ErrorCode);

public sealed record LiveSessionStatistics(
    Guid SessionId,
    string Source,
    long? BytesReceived,
    long? BytesSent,
    double? FramesPerSecond,
    double? RoundTripTimeMilliseconds,
    double? JitterMilliseconds,
    long? PacketsLost,
    int? FrameWidth,
    int? FrameHeight,
    DateTimeOffset SampledAtUtc);

public sealed record LivePreviewVisibility(
    Guid SessionId,
    bool Visible);

public sealed record LiveSessionError(
    Guid SessionId,
    string Code,
    string Message);

public sealed record LiveWallMetricsView(
    DateTimeOffset SampledAtUtc,
    int ActiveViewerCount,
    int ActiveTileSessions,
    int ActivePublishers,
    int ReconnectCount,
    double? AverageFirstFrameMilliseconds,
    long? AggregateEstimatedBandwidthBitsPerSecond);
