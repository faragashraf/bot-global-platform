using SentriCam.Contracts.CameraControl;

namespace SentriCam.Application.CameraControl;

public sealed record CameraControlActor(string Subject);

public sealed record StoredCameraControlDevice(
    Guid DeviceId,
    CameraControlSettings DesiredSettings,
    CameraControlDeviceReport? DeviceState,
    IReadOnlyList<CameraControlCommandView> Commands,
    IReadOnlyList<CameraControlAuditEntry> Audit,
    DateTimeOffset UpdatedAtUtc,
    string? LastRestoreConnectionId = null,
    string? StateConnectionId = null);

public interface ICameraControlStore
{
    Task<StoredCameraControlDevice?> GetAsync(Guid deviceId, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<StoredCameraControlDevice>> GetAllAsync(CancellationToken cancellationToken = default);
    Task SaveAsync(StoredCameraControlDevice device, CancellationToken cancellationToken = default);
}

public interface ICameraControlDispatcher
{
    Task DispatchPendingAsync(CancellationToken cancellationToken = default);
}

public interface ICameraControlTransport
{
    Task<bool> DispatchAsync(
        string deviceConnectionId,
        CameraControlCommandEnvelope command,
        CancellationToken cancellationToken = default);

    Task CancelAsync(
        string deviceConnectionId,
        CameraControlCancellation cancellation,
        CancellationToken cancellationToken = default);
}

public interface ICameraControlEventPublisher
{
    Task ChangedAsync(CameraControlUpdate update, CancellationToken cancellationToken = default);
}

public sealed class NullCameraControlTransport : ICameraControlTransport
{
    public Task<bool> DispatchAsync(string deviceConnectionId, CameraControlCommandEnvelope command, CancellationToken cancellationToken = default) => Task.FromResult(false);
    public Task CancelAsync(string deviceConnectionId, CameraControlCancellation cancellation, CancellationToken cancellationToken = default) => Task.CompletedTask;
}

public sealed class NullCameraControlEventPublisher : ICameraControlEventPublisher
{
    public Task ChangedAsync(CameraControlUpdate update, CancellationToken cancellationToken = default) => Task.CompletedTask;
}

public interface ICameraControlEngine
{
    Task<CameraControlCenterView> GetAsync(Guid deviceId, CancellationToken cancellationToken = default);
    Task<CameraControlCommandView> SubmitAsync(Guid deviceId, CameraControlCommandRequest request, CameraControlActor actor, CancellationToken cancellationToken = default);
    Task<CameraControlCommandView> CancelAsync(Guid deviceId, Guid commandId, CameraControlActor actor, CancellationToken cancellationToken = default);
    Task<int> ResolveForDeviceRemovalAsync(Guid deviceId, CameraControlActor actor, CancellationToken cancellationToken = default);
    Task ReportAsync(Guid authenticatedDeviceId, string connectionId, CameraControlDeviceReport report, CancellationToken cancellationToken = default);
    Task CompleteAsync(Guid authenticatedDeviceId, string connectionId, CameraControlCommandResult result, CancellationToken cancellationToken = default);
}
