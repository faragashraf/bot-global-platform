using System.Collections.Concurrent;
using System.Runtime.CompilerServices;
using System.Threading.Channels;
using SentriCam.Application.Recordings;

namespace SentriCam.Infrastructure.Storage;

public sealed class RecordingThumbnailScheduler : IRecordingThumbnailScheduler
{
    private readonly Channel<Guid> _channel = Channel.CreateUnbounded<Guid>(
        new UnboundedChannelOptions { SingleReader = true, SingleWriter = false });
    private readonly ConcurrentDictionary<Guid, byte> _scheduled = new();

    public bool Enqueue(Guid recordingId)
    {
        if (recordingId == Guid.Empty || !_scheduled.TryAdd(recordingId, 0))
        {
            return false;
        }
        if (_channel.Writer.TryWrite(recordingId))
        {
            return true;
        }
        _scheduled.TryRemove(recordingId, out _);
        return false;
    }

    public async IAsyncEnumerable<Guid> ReadAllAsync(
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        await foreach (var recordingId in _channel.Reader.ReadAllAsync(cancellationToken))
        {
            yield return recordingId;
        }
    }

    public void Complete(Guid recordingId) => _scheduled.TryRemove(recordingId, out _);
}
