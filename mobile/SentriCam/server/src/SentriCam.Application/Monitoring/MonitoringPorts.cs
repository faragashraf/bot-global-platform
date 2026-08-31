using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Monitoring;
using SentriCam.Domain.Devices;

namespace SentriCam.Application.Monitoring;

public interface IDeviceCommandTransport
{
    Task<bool> DispatchAsync(
        DeviceCommandEnvelope command,
        CancellationToken cancellationToken = default);
}

public interface IMonitoringEventPublisher
{
    Task DeviceChangedAsync(
        MonitoringUpdate update,
        CancellationToken cancellationToken = default);

    Task CommandChangedAsync(
        CommandUpdate update,
        CancellationToken cancellationToken = default);
}

public sealed class NullDeviceCommandTransport : IDeviceCommandTransport
{
    public Task<bool> DispatchAsync(
        DeviceCommandEnvelope command,
        CancellationToken cancellationToken = default) => Task.FromResult(false);
}

public sealed class NullMonitoringEventPublisher : IMonitoringEventPublisher
{
    public Task DeviceChangedAsync(
        MonitoringUpdate update,
        CancellationToken cancellationToken = default) => Task.CompletedTask;

    public Task CommandChangedAsync(
        CommandUpdate update,
        CancellationToken cancellationToken = default) => Task.CompletedTask;
}

public interface IRemoteMonitoringService
{
    Task<IReadOnlyList<DeviceLiveState>> GetDevicesAsync(
        CancellationToken cancellationToken = default);

    Task<DeviceMonitoringDetails> GetDeviceAsync(
        Guid deviceId,
        CancellationToken cancellationToken = default);

    Task<RemoteCommandView> SubmitAsync(
        IDeviceControlCommand command,
        CancellationToken cancellationToken = default);

    Task CompleteAsync(
        Guid authenticatedDeviceId,
        DeviceCommandResultEnvelope result,
        CancellationToken cancellationToken = default);

    Task<int> ExpireCommandsAsync(CancellationToken cancellationToken = default);
}
