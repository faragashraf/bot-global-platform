using SentriCam.Domain.Devices;
using SentriCam.Domain.Recordings;
using SentriCam.Application.Recordings;

namespace SentriCam.Application.Abstractions.Persistence;

public interface IRecordingRepository
{
    Task<Recording?> GetByIdAsync(Guid id, CancellationToken cancellationToken = default);
    Task<Recording?> GetByClientRecordingIdAsync(
        DeviceId deviceId,
        string clientRecordingId,
        CancellationToken cancellationToken = default);
    Task<PagedRecordingProjection> QueryAsync(
        RecordingQuery query,
        CancellationToken cancellationToken = default);
    Task<IReadOnlyList<RecordingTimeBucket>> AggregateTimeAsync(
        RecordingTimeQuery query,
        CancellationToken cancellationToken = default);
    Task<IReadOnlyList<Guid>> ListPendingThumbnailIdsAsync(
        int maximumCount,
        DateTimeOffset staleBeforeUtc,
        CancellationToken cancellationToken = default);
    void Add(Recording recording);
    void Remove(Recording recording);
}
