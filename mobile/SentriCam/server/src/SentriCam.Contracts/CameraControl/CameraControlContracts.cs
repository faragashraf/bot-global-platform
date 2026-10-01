using System.Text.Json.Serialization;
using SentriCam.Contracts.Motion;

namespace SentriCam.Contracts.CameraControl;

public static class CameraControlIds
{
    public const string Recording = "recording";
    public const string Lens = "lens";
    public const string Zoom = "zoom";
    public const string Torch = "torch";
    public const string ExposureCompensation = "exposureCompensation";
    public const string Preview = "preview";
    public const string FramesPerSecond = "framesPerSecond";
    public const string Resolution = "resolution";
    public const string Bitrate = "bitrate";
    public const string Quality = "quality";
    public const string NightProfile = "nightProfile";
    public const string DateTimeOverlay = "dateTimeOverlay";
    public const string Restore = "restore";
}

public static class DateTimeOverlayPositions
{
    public const string TopLeft = "topLeft";
    public const string TopRight = "topRight";
    public const string BottomLeft = "bottomLeft";
    public const string BottomRight = "bottomRight";

    public static IReadOnlyList<string> All { get; } = [TopLeft, TopRight, BottomLeft, BottomRight];
}

public sealed record DateTimeOverlayConfiguration(
    bool Enabled = false,
    bool DateEnabled = true,
    bool TimeEnabled = true,
    bool Use24HourTime = true,
    string Position = DateTimeOverlayPositions.BottomLeft);

public static class CameraControlValues
{
    public const string Start = "start";
    public const string Stop = "stop";
    public const string Front = "front";
    public const string Back = "back";
    public const string Visible = "visible";
    public const string Hidden = "hidden";
    public const string Dimmed = "dimmed";
    public const string Auto = "auto";
    public const string Low = "low";
    public const string Medium = "medium";
    public const string High = "high";
    public const string Day = "day";
    public const string Night = "night";
    public const string Indoor = "indoor";
    public const string Outdoor = "outdoor";
}

[JsonConverter(typeof(JsonStringEnumConverter))]
public enum CameraControlCommandState
{
    Queued = 1,
    Executing = 2,
    Retrying = 3,
    Succeeded = 4,
    Failed = 5,
    Canceled = 6,
}

public sealed record CameraControlValue(
    bool? Boolean = null,
    double? Number = null,
    string? Text = null,
    DateTimeOverlayConfiguration? DateTimeOverlay = null);

public sealed record CameraCapabilityDescriptor(
    string Id,
    bool Supported,
    bool Writable,
    CameraControlValue? CurrentValue,
    double? Minimum = null,
    double? Maximum = null,
    double? Step = null,
    IReadOnlyList<string>? AllowedValues = null,
    string? Unit = null,
    string? Reason = null);

public sealed record CameraControlSettings(
    string Lens,
    double Zoom,
    bool Torch,
    int ExposureCompensation,
    string Preview,
    int FramesPerSecond,
    string Resolution,
    int Bitrate,
    string Quality,
    string NightProfile,
    DateTimeOverlayConfiguration? DateTimeOverlay = null)
{
    public static CameraControlSettings Default { get; } = new(
        CameraControlValues.Back,
        1,
        false,
        0,
        CameraControlValues.Visible,
        30,
        "1280x720",
        2_500_000,
        CameraControlValues.Medium,
        CameraControlValues.Auto,
        new DateTimeOverlayConfiguration());
}

public sealed record CameraControlTelemetry(
    bool CameraOnline,
    bool Streaming,
    bool Recording,
    bool MotionArmed,
    int? BatteryPercent,
    double? TemperatureCelsius,
    long? AvailableStorageBytes,
    bool? Charging,
    string ConnectionQuality,
    bool PreviewAvailable,
    bool AudioAvailable,
    string RecordingState = "idle",
    string RecordingOrigin = "none",
    CameraControlRecordingUpload? LatestRecordingUpload = null);

public sealed record CameraControlRecordingUpload(
    string ClientRecordingId,
    string State,
    int ProgressPercent,
    string? LastErrorCode,
    string? ServerRecordingId,
    DateTimeOffset RecordedAtUtc,
    DateTimeOffset? UploadedAtUtc);

public sealed record CameraControlDeviceReport(
    Guid DeviceId,
    CameraControlSettings Settings,
    IReadOnlyList<CameraCapabilityDescriptor> Capabilities,
    CameraControlTelemetry Telemetry,
    DateTimeOffset ReportedAtUtc,
    MotionSettingsDeviceReport? MotionSettings = null);

public sealed record CameraControlCommandRequest(
    string Control,
    CameraControlValue Value,
    string? CorrelationId = null,
    long? ExpectedVersion = null);

public sealed record CameraControlGroupCommandRequest(
    IReadOnlyList<Guid> DeviceIds,
    string Control,
    CameraControlValue Value,
    string? CorrelationId = null);

public sealed record CameraControlGroupCommandItem(
    Guid DeviceId,
    bool Succeeded,
    CameraControlCommandView? Command,
    string? ErrorCode,
    string? Message);

public sealed record CameraControlGroupCommandResult(
    string CorrelationId,
    IReadOnlyList<CameraControlGroupCommandItem> Items,
    int Succeeded,
    int Failed);

public sealed record CameraControlCommandView(
    Guid CommandId,
    Guid DeviceId,
    string Control,
    CameraControlValue Value,
    CameraControlCommandState State,
    int Attempts,
    bool Cancelable,
    bool Retriable,
    string CorrelationId,
    string ActorId,
    string? ResultCode,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset? CompletedAtUtc,
    long? ExpectedVersion = null);

public sealed record CameraControlCenterView(
    Guid DeviceId,
    bool Online,
    CameraControlSettings DesiredSettings,
    CameraControlDeviceReport? DeviceState,
    IReadOnlyList<CameraControlCommandView> RecentCommands,
    DateTimeOffset UpdatedAtUtc);

public sealed record CameraControlCommandEnvelope(
    Guid CommandId,
    Guid DeviceId,
    string Control,
    CameraControlValue Value,
    CameraControlSettings DesiredSettings,
    int Attempt,
    DateTimeOffset RequestedAtUtc,
    long? ExpectedVersion = null);

public sealed record CameraControlCommandResult(
    Guid CommandId,
    Guid DeviceId,
    bool Succeeded,
    bool TransientFailure,
    string ResultCode,
    CameraControlDeviceReport DeviceState,
    DateTimeOffset CompletedAtUtc);

public sealed record CameraControlCancellation(Guid CommandId, Guid DeviceId);

public sealed record CameraControlUpdate(
    Guid DeviceId,
    Guid? CommandId,
    string Reason,
    DateTimeOffset ChangedAtUtc);

public sealed record CameraControlAuditEntry(
    Guid AuditId,
    Guid DeviceId,
    Guid? CommandId,
    string ActorId,
    string Action,
    string Outcome,
    string CorrelationId,
    DateTimeOffset OccurredAtUtc);
