namespace SentriCam.Contracts.Audit;

public enum AuditActorType
{
    System = 1,
    Device = 2,
    User = 3,
    Service = 4,
}

public enum AuditSource
{
    Api = 1,
    SignalR = 2,
    BackgroundJob = 3,
    Device = 4,
    System = 5,
}

public enum AuditSeverity
{
    Information = 1,
    Warning = 2,
    Error = 3,
    Critical = 4,
}

public sealed record AuditActor(
    string ActorId,
    AuditActorType ActorType,
    string? DisplayName = null);

public sealed record AuditEntry(
    Guid AuditId,
    DateTimeOffset OccurredAtUtc,
    string Action,
    AuditActor Actor,
    AuditSource Source,
    AuditSeverity Severity,
    string? ResourceType = null,
    string? ResourceId = null,
    string? CorrelationId = null,
    string? DataJson = null);
