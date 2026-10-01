using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Abstractions.Messaging;
using SentriCam.Application.Messaging;
using SentriCam.Application.Registration;
using SentriCam.Application.Monitoring;
using SentriCam.Application.Recordings;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Monitoring;
using SentriCam.Contracts.Events;
using SentriCam.Contracts.Registration;
using SentriCam.Contracts.Recordings;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Recordings;

namespace SentriCam.Tests.TestDoubles;

internal sealed class FakeDeviceRepository : IDeviceRepository
{
    public List<Device> Devices { get; } = [];

    public Task<IReadOnlyList<Device>> ListAsync(
        CancellationToken cancellationToken = default) =>
        Task.FromResult<IReadOnlyList<Device>>(Devices);

    public Task<Device?> GetByIdAsync(
        DeviceId id,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(Devices.SingleOrDefault(device => device.Id == id));

    public Task<Device?> GetByInstallationIdAsync(
        string installationId,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(Devices.SingleOrDefault(device =>
            string.Equals(
                device.Identity.InstallationId,
                installationId,
                StringComparison.OrdinalIgnoreCase)));

    public void Add(Device device) => Devices.Add(device);
}

internal sealed class FakeRegistrationRepository : IDeviceRegistrationRepository
{
    public List<DeviceRegistration> Registrations { get; } = [];

    public Task<DeviceRegistration?> GetLatestAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(Registrations
            .Where(registration => registration.DeviceId == deviceId)
            .OrderByDescending(registration => registration.RegisteredAtUtc)
            .ThenByDescending(registration => registration.Id)
            .FirstOrDefault());

    public void Add(DeviceRegistration registration) => Registrations.Add(registration);
}

internal sealed class FakeCommandRepository : IDeviceCommandRepository
{
    public List<DeviceCommand> Commands { get; } = [];

    public Task<IReadOnlyList<DeviceCommand>> GetRecentAsync(
        DeviceId deviceId,
        int count,
        CancellationToken cancellationToken = default) =>
        Task.FromResult<IReadOnlyList<DeviceCommand>>(Commands
            .Where(command => command.DeviceId == deviceId)
            .OrderByDescending(command => command.RequestedAtUtc)
            .Take(count)
            .ToArray());

    public Task<IReadOnlyList<DeviceCommand>> GetExpiredAsync(
        DateTimeOffset utcNow,
        CancellationToken cancellationToken = default) =>
        Task.FromResult<IReadOnlyList<DeviceCommand>>(Commands
            .Where(command => command.ExpiresAtUtc <= utcNow
                && command.State is DeviceCommandState.Pending or DeviceCommandState.Dispatched)
            .ToArray());

    public Task<IReadOnlyList<DeviceCommand>> GetActiveAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default) =>
        Task.FromResult<IReadOnlyList<DeviceCommand>>(Commands
            .Where(command => command.DeviceId == deviceId
                && command.State is DeviceCommandState.Pending or DeviceCommandState.Dispatched)
            .ToArray());

    public Task<DeviceCommand?> GetByIdAsync(
        Guid id,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(Commands.SingleOrDefault(command => command.Id == id));

    public Task<DeviceCommand?> GetByCorrelationIdAsync(
        DeviceId deviceId,
        string correlationId,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(Commands.SingleOrDefault(command =>
            command.DeviceId == deviceId
            && string.Equals(command.CorrelationId, correlationId, StringComparison.Ordinal)));

    public void Add(DeviceCommand command) => Commands.Add(command);
}

internal sealed class FakeDeviceConnectionRepository : IDeviceConnectionRepository
{
    public List<DeviceConnection> Connections { get; } = [];

    public Task<DeviceConnection?> GetActiveAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(Connections.SingleOrDefault(connection =>
            connection.DeviceId == deviceId && connection.IsActive));

    public Task<DeviceConnection?> GetByTransportConnectionIdAsync(
        string transportConnectionId,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(Connections.SingleOrDefault(connection =>
            string.Equals(
                connection.TransportConnectionId,
                transportConnectionId,
                StringComparison.Ordinal)));

    public void Add(DeviceConnection connection) => Connections.Add(connection);
}

internal sealed class FakeDeviceSnapshotRepository : IDeviceSnapshotRepository
{
    public List<DeviceSnapshot> Snapshots { get; } = [];

    public Task<DeviceSnapshot?> GetLatestAsync(
        DeviceId deviceId,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(Snapshots
            .Where(snapshot => snapshot.DeviceId == deviceId)
            .MaxBy(snapshot => snapshot.SnapshotVersion));

    public Task<DeviceSnapshot?> GetByVersionAsync(
        DeviceId deviceId,
        long snapshotVersion,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(Snapshots.SingleOrDefault(snapshot =>
            snapshot.DeviceId == deviceId && snapshot.SnapshotVersion == snapshotVersion));

    public Task<IReadOnlyList<DeviceSnapshot>> GetRecentAsync(
        DeviceId deviceId,
        int count,
        CancellationToken cancellationToken = default) =>
        Task.FromResult<IReadOnlyList<DeviceSnapshot>>(Snapshots
            .Where(snapshot => snapshot.DeviceId == deviceId)
            .OrderByDescending(snapshot => snapshot.SnapshotVersion)
            .Take(count)
            .ToArray());

    public void Add(DeviceSnapshot snapshot) => Snapshots.Add(snapshot);
}

internal sealed class FakeRecordingRepository : IRecordingRepository
{
    public List<Recording> Recordings { get; } = [];

    public Task<Recording?> GetByIdAsync(Guid id, CancellationToken cancellationToken = default) =>
        Task.FromResult(Recordings.SingleOrDefault(recording => recording.Id == id));

    public Task<Recording?> GetByClientRecordingIdAsync(
        DeviceId deviceId,
        string clientRecordingId,
        CancellationToken cancellationToken = default) =>
        Task.FromResult(Recordings.SingleOrDefault(recording =>
            recording.DeviceId == deviceId
            && recording.ClientRecordingId == clientRecordingId));

    public Task<PagedRecordingProjection> QueryAsync(
        RecordingQuery query,
        CancellationToken cancellationToken = default)
    {
        IEnumerable<Recording> values = Recordings;
        if (query.DeviceIds is { Count: > 0 }) values = values.Where(value => query.DeviceIds.Contains(value.DeviceId.Value));
        if (query.Source == RecordingSourceFilter.Motion) values = values.Where(value => value.IsMotion);
        if (query.Source == RecordingSourceFilter.Manual) values = values.Where(value => value.IsManual);
        if (query.DateFromUtc is not null) values = values.Where(value => value.CreatedUtc >= query.DateFromUtc);
        if (query.DateToUtc is not null) values = values.Where(value => value.CreatedUtc < query.DateToUtc);
        if (query.MinimumDurationMilliseconds is not null) values = values.Where(value => value.DurationMilliseconds >= query.MinimumDurationMilliseconds);
        if (query.MaximumDurationMilliseconds is not null) values = values.Where(value => value.DurationMilliseconds <= query.MaximumDurationMilliseconds);
        if (query.MinimumSizeBytes is not null) values = values.Where(value => value.SizeBytes >= query.MinimumSizeBytes);
        if (query.MaximumSizeBytes is not null) values = values.Where(value => value.SizeBytes <= query.MaximumSizeBytes);
        if (!string.IsNullOrWhiteSpace(query.Search)) values = values.Where(value => value.OriginalFileName.Contains(query.Search, StringComparison.OrdinalIgnoreCase));
        values = query.Sort switch
        {
            RecordingSort.Oldest => values.OrderBy(value => value.CreatedUtc).ThenBy(value => value.Id),
            RecordingSort.Longest => values.OrderByDescending(value => value.DurationMilliseconds).ThenBy(value => value.Id),
            RecordingSort.Shortest => values.OrderBy(value => value.DurationMilliseconds).ThenBy(value => value.Id),
            RecordingSort.Largest => values.OrderByDescending(value => value.SizeBytes).ThenBy(value => value.Id),
            RecordingSort.Smallest => values.OrderBy(value => value.SizeBytes).ThenBy(value => value.Id),
            _ => values.OrderByDescending(value => value.CreatedUtc).ThenBy(value => value.Id),
        };
        var array = values.ToArray();
        var items = array
            .Skip((query.Page - 1) * query.PageSize)
            .Take(query.PageSize)
            .Select(value => new RecordingProjection(
                value.Id,
                value.DeviceId.Value,
                "Device",
                value.ClientRecordingId,
                value.SessionId,
                value.OriginalFileName,
                value.ContentType,
                value.DurationMilliseconds,
                value.SizeBytes,
                value.CreatedUtc,
                value.UploadedUtc,
                value.IsMotion,
                value.IsManual,
                (int)value.ThumbnailState,
                value.ThumbnailGenerationAttempts,
                value.ThumbnailErrorCode,
                value.ThumbnailGeneratedUtc,
                value.ChecksumSha256,
                value.ThumbnailRelativePath is not null))
            .ToArray();
        return Task.FromResult(new PagedRecordingProjection(items, array.LongLength));
    }

    public Task<IReadOnlyList<RecordingTimeBucket>> AggregateTimeAsync(
        RecordingTimeQuery query,
        CancellationToken cancellationToken = default)
    {
        var values = Recordings
            .Where(value => query.ParentStartUtc is null || value.CreatedUtc >= query.ParentStartUtc)
            .Where(value => query.ParentEndUtc is null || value.CreatedUtc < query.ParentEndUtc);
        var buckets = query.Level switch
        {
            RecordingTimeLevel.Year => values.GroupBy(value => value.CreatedUtc.Year).Select(group => new RecordingTimeBucket(group.Key, null, null, null, group.LongCount(), group.Sum(value => value.SizeBytes))),
            RecordingTimeLevel.Month => values.GroupBy(value => new { value.CreatedUtc.Year, value.CreatedUtc.Month }).Select(group => new RecordingTimeBucket(group.Key.Year, group.Key.Month, null, null, group.LongCount(), group.Sum(value => value.SizeBytes))),
            RecordingTimeLevel.Week or RecordingTimeLevel.Day => values.GroupBy(value => new { value.CreatedUtc.Year, value.CreatedUtc.Month, value.CreatedUtc.Day }).Select(group => new RecordingTimeBucket(group.Key.Year, group.Key.Month, group.Key.Day, null, group.LongCount(), group.Sum(value => value.SizeBytes))),
            RecordingTimeLevel.Hour => values.GroupBy(value => new { value.CreatedUtc.Year, value.CreatedUtc.Month, value.CreatedUtc.Day, value.CreatedUtc.Hour }).Select(group => new RecordingTimeBucket(group.Key.Year, group.Key.Month, group.Key.Day, group.Key.Hour, group.LongCount(), group.Sum(value => value.SizeBytes))),
            _ => [],
        };
        return Task.FromResult<IReadOnlyList<RecordingTimeBucket>>(buckets.ToArray());
    }

    public Task<IReadOnlyList<Guid>> ListPendingThumbnailIdsAsync(
        int maximumCount,
        DateTimeOffset staleBeforeUtc,
        CancellationToken cancellationToken = default) =>
        Task.FromResult<IReadOnlyList<Guid>>(Recordings
            .Where(value => value.ThumbnailState == ThumbnailGenerationState.Pending)
            .Take(maximumCount)
            .Select(value => value.Id)
            .ToArray());

    public void Add(Recording recording) => Recordings.Add(recording);

    public void Remove(Recording recording) => Recordings.Remove(recording);
}

internal sealed class FakeDeviceRequestAuthorizer(DeviceId allowedDeviceId)
    : IDeviceRequestAuthorizer
{
    public void EnsureCanAccess(DeviceId deviceId)
    {
        if (deviceId != allowedDeviceId)
        {
            throw new InvalidOperationException("Test device access was denied.");
        }
    }
}

internal sealed class FakeUnitOfWork(bool failOnSave = false) : IUnitOfWork
{
    public int SaveCount { get; private set; }

    public Task<int> SaveChangesAsync(CancellationToken cancellationToken = default)
    {
        SaveCount++;
        if (failOnSave)
        {
            throw new InvalidOperationException("Simulated persistence failure.");
        }
        return Task.FromResult(1);
    }
}

internal sealed class FakeTokenIssuer : IDeviceAccessTokenIssuer
{
    public DeviceAccessToken Issue(
        DeviceId deviceId,
        string installationId,
        DateTimeOffset issuedAtUtc) =>
        Issue(deviceId, installationId, Guid.NewGuid(), issuedAtUtc);

    public DeviceAccessToken Issue(
        DeviceId deviceId,
        string installationId,
        Guid registrationId,
        DateTimeOffset issuedAtUtc) =>
        new("stub-device-token", issuedAtUtc.AddHours(1));
}

internal sealed class FixedTimeProvider(DateTimeOffset value) : TimeProvider
{
    public override DateTimeOffset GetUtcNow() => value;
}

internal sealed class MutableTimeProvider(DateTimeOffset value) : TimeProvider
{
    public DateTimeOffset Value { get; set; } = value;

    public override DateTimeOffset GetUtcNow() => Value;
}

internal sealed class FakeDeviceCommandTransport(bool dispatchResult = true) : IDeviceCommandTransport
{
    public bool DispatchResult { get; set; } = dispatchResult;
    public List<DeviceCommandEnvelope> Dispatched { get; } = [];

    public Task<bool> DispatchAsync(
        DeviceCommandEnvelope command,
        CancellationToken cancellationToken = default)
    {
        Dispatched.Add(command);
        return Task.FromResult(DispatchResult);
    }
}

internal sealed class FakeMonitoringEventPublisher : IMonitoringEventPublisher
{
    public List<MonitoringUpdate> DeviceUpdates { get; } = [];

    public List<CommandUpdate> CommandUpdates { get; } = [];

    public Task DeviceChangedAsync(
        MonitoringUpdate update,
        CancellationToken cancellationToken = default)
    {
        DeviceUpdates.Add(update);
        return Task.CompletedTask;
    }

    public Task CommandChangedAsync(
        CommandUpdate update,
        CancellationToken cancellationToken = default)
    {
        CommandUpdates.Add(update);
        return Task.CompletedTask;
    }
}

internal sealed class FakeRegistrationService(RegistrationResult result) : IRegistrationService
{
    public RegisterDeviceCall? LastCall { get; private set; }

    public Task<RegistrationResult> RegisterAsync(
        RegistrationRequest request,
        CancellationToken cancellationToken = default)
    {
        LastCall = new RegisterDeviceCall(request, cancellationToken);
        return Task.FromResult(result);
    }

    public Task<RegistrationResult> RegisterFromPairingAsync(
        RegistrationRequest request,
        CancellationToken cancellationToken = default) => RegisterAsync(request, cancellationToken);
}

internal sealed record RegisterDeviceCall(
    RegistrationRequest Request,
    CancellationToken CancellationToken);

internal sealed class FakeOutboxStore(params OutboxMessage[] messages) : IOutboxStore
{
    public Task<IReadOnlyList<OutboxMessage>> GetPendingAsync(
        int batchSize,
        CancellationToken cancellationToken = default) =>
        Task.FromResult<IReadOnlyList<OutboxMessage>>(messages.Take(batchSize).ToArray());
}

internal sealed class FakeIntegrationEventPublisher : IIntegrationEventPublisher
{
    public List<IntegrationEvent> PublishedEvents { get; } = [];

    public Task PublishAsync(
        IntegrationEvent integrationEvent,
        CancellationToken cancellationToken = default)
    {
        PublishedEvents.Add(integrationEvent);
        return Task.CompletedTask;
    }
}
