using SentriCam.Domain.Common;

namespace SentriCam.Domain.Devices;

public enum DeviceCommandKind
{
    StartMonitoring = 1,
    StopMonitoring = 2,
    StartRecording = 3,
    StopRecording = 4,
    RestartMonitoring = 5,
    Ping = 6,
    GetStatus = 7,
}

public enum DeviceCommandState
{
    Pending = 1,
    Dispatched = 2,
    Succeeded = 3,
    Failed = 4,
    TimedOut = 5,
}

public enum DeviceCommandOrigin
{
    Application = 1,
    Operator = 2,
    Automation = 3,
    System = 4,
}

public sealed class DeviceCommand : AggregateRoot<Guid>
{
    private DeviceCommand()
    {
    }

    private DeviceCommand(
        Guid id,
        DeviceId deviceId,
        DeviceCommandKind kind,
        string correlationId,
        DateTimeOffset requestedAtUtc,
        DateTimeOffset? expiresAtUtc,
        DeviceCommandOrigin origin)
    {
        Id = id;
        DeviceId = deviceId;
        Kind = kind;
        CorrelationId = correlationId;
        State = DeviceCommandState.Pending;
        RequestedAtUtc = requestedAtUtc;
        ExpiresAtUtc = expiresAtUtc;
        Origin = origin;
    }

    public DeviceId DeviceId { get; private set; }

    public Guid CommandId => Id;

    public DeviceCommandKind Kind { get; private set; }

    public string CorrelationId { get; private set; } = string.Empty;

    public DeviceCommandState State { get; private set; }

    public DateTimeOffset RequestedAtUtc { get; private set; }

    public DateTimeOffset CreatedAtUtc => RequestedAtUtc;

    public DateTimeOffset? ExpiresAtUtc { get; private set; }

    public DeviceCommandOrigin Origin { get; private set; }

    public DateTimeOffset? DispatchedAtUtc { get; private set; }

    public DateTimeOffset? CompletedAtUtc { get; private set; }

    public string? FailureReason { get; private set; }

    public string? ResultCode { get; private set; }

    public string? ResultJson { get; private set; }

    public static DeviceCommand Queue(
        DeviceId deviceId,
        DeviceCommandKind kind,
        string correlationId,
        DateTimeOffset now) =>
        Queue(
            deviceId,
            kind,
            correlationId,
            now,
            expiresAtUtc: null,
            DeviceCommandOrigin.Application);

    public static DeviceCommand Queue(
        DeviceId deviceId,
        DeviceCommandKind kind,
        string correlationId,
        DateTimeOffset createdAtUtc,
        DateTimeOffset? expiresAtUtc,
        DeviceCommandOrigin origin)
    {
        if (expiresAtUtc <= createdAtUtc)
        {
            throw new DomainValidationException("Command expiration must be after creation time.");
        }

        return new DeviceCommand(
            Guid.NewGuid(),
            deviceId,
            kind,
            DomainGuard.Required(correlationId, 100, nameof(CorrelationId)),
            createdAtUtc,
            expiresAtUtc,
            origin);
    }

    public bool IsExpiredAt(DateTimeOffset utcNow) => ExpiresAtUtc <= utcNow;

    public void MarkDispatched(DateTimeOffset now)
    {
        if (State == DeviceCommandState.Dispatched)
        {
            return;
        }

        EnsureState(DeviceCommandState.Pending);
        State = DeviceCommandState.Dispatched;
        DispatchedAtUtc = now;
    }

    public void Succeed(string resultCode, string? resultJson, DateTimeOffset now)
    {
        if (State == DeviceCommandState.Succeeded)
        {
            return;
        }

        EnsureState(DeviceCommandState.Dispatched);
        State = DeviceCommandState.Succeeded;
        ResultCode = DomainGuard.Required(resultCode, 100, nameof(ResultCode));
        ResultJson = DomainGuard.Optional(resultJson, 16_000, nameof(ResultJson));
        CompletedAtUtc = now;
    }

    public void Fail(string reason, DateTimeOffset now)
    {
        if (State == DeviceCommandState.Failed)
        {
            return;
        }

        if (State == DeviceCommandState.Succeeded)
        {
            throw new DomainValidationException("A completed command cannot transition to failed.");
        }

        State = DeviceCommandState.Failed;
        FailureReason = DomainGuard.Required(reason, 500, nameof(FailureReason));
        CompletedAtUtc = now;
    }

    public void Timeout(DateTimeOffset now)
    {
        if (State == DeviceCommandState.TimedOut)
        {
            return;
        }

        if (State is DeviceCommandState.Succeeded or DeviceCommandState.Failed)
        {
            throw new DomainValidationException("A completed command cannot time out.");
        }

        State = DeviceCommandState.TimedOut;
        FailureReason = "command_timeout";
        ResultCode = "timeout";
        CompletedAtUtc = now;
    }

    private void EnsureState(DeviceCommandState expected)
    {
        if (State != expected)
        {
            throw new DomainValidationException($"Command must be {expected} to perform this transition.");
        }
    }
}
