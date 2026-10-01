using System.Text.Json;
using System.Text.Json.Serialization;
using SentriCam.Application.CameraControl;
using SentriCam.Contracts.CameraControl;

namespace SentriCam.Infrastructure.CameraControl;

public sealed class FileCameraControlStore : ICameraControlStore, IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = true,
    };
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly string _path;
    private Dictionary<Guid, StoredCameraControlDevice> _devices;

    public FileCameraControlStore(string hubRootPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(hubRootPath);
        var root = Path.Combine(Path.GetFullPath(hubRootPath), "camera-control");
        Directory.CreateDirectory(root);
        _path = Path.Combine(root, "state.json");
        _devices = Read(_path);
    }

    public async Task<StoredCameraControlDevice?> GetAsync(
        Guid deviceId,
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken);
        try
        {
            return _devices.GetValueOrDefault(deviceId);
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<IReadOnlyList<StoredCameraControlDevice>> GetAllAsync(
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken);
        try
        {
            return _devices.Values.ToArray();
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task SaveAsync(
        StoredCameraControlDevice device,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(device);
        await _gate.WaitAsync(cancellationToken);
        try
        {
            var next = new Dictionary<Guid, StoredCameraControlDevice>(_devices)
            {
                [device.DeviceId] = device,
            };
            var temporaryPath = _path + ".tmp";
            await using (var stream = new FileStream(
                temporaryPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                16_384,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await JsonSerializer.SerializeAsync(stream, next, JsonOptions, cancellationToken);
                await stream.FlushAsync(cancellationToken);
            }
            File.Move(temporaryPath, _path, overwrite: true);
            _devices = next;
        }
        finally
        {
            _gate.Release();
        }
    }

    public void Dispose() => _gate.Dispose();

    private static Dictionary<Guid, StoredCameraControlDevice> Read(string path)
    {
        if (!File.Exists(path)) return [];
        try
        {
            using var stream = File.OpenRead(path);
            var devices = JsonSerializer.Deserialize<Dictionary<Guid, StoredCameraControlDevice>>(stream, JsonOptions) ?? [];
            return devices.ToDictionary(
                item => item.Key,
                item => Normalize(item.Value));
        }
        catch (JsonException exception)
        {
            throw new InvalidOperationException("The persisted Camera Control state is invalid.", exception);
        }
    }

    private static StoredCameraControlDevice Normalize(StoredCameraControlDevice device) => device with
    {
        DesiredSettings = Normalize(device.DesiredSettings),
        DeviceState = device.DeviceState is null
            ? null
            : device.DeviceState with { Settings = Normalize(device.DeviceState.Settings) },
    };

    private static CameraControlSettings Normalize(CameraControlSettings settings) =>
        settings.DateTimeOverlay is null
            ? settings with { DateTimeOverlay = new DateTimeOverlayConfiguration() }
            : settings;
}
