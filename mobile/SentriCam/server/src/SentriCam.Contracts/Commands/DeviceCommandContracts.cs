using System.Text.Json;
using SentriCam.Contracts.Registration;

namespace SentriCam.Contracts.Commands;

public enum DeviceCommandType
{
    StartMonitoring = 1,
    StopMonitoring = 2,
    StartRecording = 3,
    StopRecording = 4,
    RestartMonitoring = 5,
    Ping = 6,
    GetStatus = 7,
}

public enum DeviceCommandOutcome
{
    Succeeded = 1,
    Failed = 2,
}

public enum DeviceOperationalStatus
{
    Registered = 1,
    Offline = 2,
    Online = 3,
    Monitoring = 4,
    Recording = 5,
    Disabled = 6,
}

public sealed record RegisterDevice(RegistrationRequest Registration);

public sealed record Ping(Guid DeviceId, DateTimeOffset SentAtUtc);

public sealed record GetStatus(Guid DeviceId);

public interface IDeviceControlCommand
{
    Guid DeviceId { get; }

    string? CorrelationId { get; }

    DeviceCommandType CommandType { get; }
}

public sealed record StartMonitoring(Guid DeviceId, string? CorrelationId = null) : IDeviceControlCommand
{
    public DeviceCommandType CommandType => DeviceCommandType.StartMonitoring;
}

public sealed record StopMonitoring(Guid DeviceId, string? CorrelationId = null) : IDeviceControlCommand
{
    public DeviceCommandType CommandType => DeviceCommandType.StopMonitoring;
}

public sealed record StartRecording(Guid DeviceId, string? CorrelationId = null) : IDeviceControlCommand
{
    public DeviceCommandType CommandType => DeviceCommandType.StartRecording;
}

public sealed record StopRecording(Guid DeviceId, string? CorrelationId = null) : IDeviceControlCommand
{
    public DeviceCommandType CommandType => DeviceCommandType.StopRecording;
}

public sealed record RestartMonitoring(Guid DeviceId, string? CorrelationId = null) : IDeviceControlCommand
{
    public DeviceCommandType CommandType => DeviceCommandType.RestartMonitoring;
}

public sealed record PingDevice(Guid DeviceId, string? CorrelationId = null) : IDeviceControlCommand
{
    public DeviceCommandType CommandType => DeviceCommandType.Ping;
}

public sealed record RefreshDeviceStatus(Guid DeviceId, string? CorrelationId = null) : IDeviceControlCommand
{
    public DeviceCommandType CommandType => DeviceCommandType.GetStatus;
}

public sealed record PingResult(
    Guid DeviceId,
    DateTimeOffset ServerTimeUtc,
    DateTimeOffset LastSeenAtUtc);

public sealed record DeviceStatusResult(
    Guid DeviceId,
    DeviceOperationalStatus Status,
    DateTimeOffset? LastSeenAtUtc,
    bool IsEnabled);

public sealed record CommandSubmissionResult(
    Guid CommandId,
    Guid DeviceId,
    DeviceCommandType CommandType,
    string CorrelationId,
    DateTimeOffset AcceptedAtUtc);

public sealed record DeviceCommandEnvelope(
    Guid CommandId,
    Guid DeviceId,
    DeviceCommandType CommandType,
    string CorrelationId,
    DateTimeOffset RequestedAtUtc);

public sealed record DeviceCommandResultEnvelope(
    Guid CommandId,
    Guid DeviceId,
    DeviceCommandOutcome Outcome,
    string ResultCode,
    JsonElement? Snapshot,
    DateTimeOffset CompletedAtUtc);
