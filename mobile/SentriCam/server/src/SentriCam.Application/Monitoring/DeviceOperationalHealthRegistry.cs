using System.Text.Json;
using SentriCam.Contracts.Monitoring;
using SentriCam.Domain.Devices;

namespace SentriCam.Application.Monitoring;

public interface IDeviceOperationalHealthRegistry
{
    void Report(DeviceId authenticatedDeviceId, DeviceOperationalHealthReport report);
    DeviceOperationalHealthReport? GetLatest(DeviceId deviceId);
    void Remove(DeviceId deviceId);
}

public sealed class DeviceOperationalHealthRegistry : IDeviceOperationalHealthRegistry
{
    private const int MaximumPayloadCharacters = 12_000;
    private readonly object _gate = new();
    private readonly Dictionary<DeviceId, DeviceOperationalHealthReport> _reports = [];

    public void Report(DeviceId authenticatedDeviceId, DeviceOperationalHealthReport report)
    {
        ArgumentNullException.ThrowIfNull(report);
        if (report.DeviceId != authenticatedDeviceId.Value)
        {
            throw new InvalidOperationException("Operational health belongs to another device.");
        }
        if (report.Health.ValueKind != JsonValueKind.Object)
        {
            throw new ArgumentException("Operational health must be a JSON object.", nameof(report));
        }

        var health = report.Health.Clone();
        if (health.GetRawText().Length > MaximumPayloadCharacters)
        {
            throw new ArgumentException("Operational health exceeds the supported size.", nameof(report));
        }

        lock (_gate)
        {
            _reports[authenticatedDeviceId] = report with { Health = health };
        }
    }

    public DeviceOperationalHealthReport? GetLatest(DeviceId deviceId)
    {
        lock (_gate)
        {
            return _reports.GetValueOrDefault(deviceId);
        }
    }

    public void Remove(DeviceId deviceId)
    {
        lock (_gate)
        {
            _reports.Remove(deviceId);
        }
    }
}
