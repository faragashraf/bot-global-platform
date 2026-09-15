using System.Collections.Concurrent;
using System.Data;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Games.Infrastructure.Persistence;

internal sealed class GamesMembershipDeletionFence(
    string applicationKey,
    Guid membershipId,
    DateTimeOffset establishedAtUtc)
{
    public string ApplicationKey { get; private set; } = applicationKey;
    public Guid MembershipId { get; private set; } = membershipId;
    public DateTimeOffset EstablishedAtUtc { get; private set; } = establishedAtUtc;
}

internal interface IGamesMembershipWriteFence
{
    Task<T> ExecuteAsync<T>(
        string applicationKey,
        Guid membershipId,
        Func<Task<T>> operation,
        Func<T> rejected,
        CancellationToken cancellationToken);

    Task EstablishDeletionBarrierAsync(
        string applicationKey,
        Guid membershipId,
        DateTimeOffset establishedAtUtc,
        CancellationToken cancellationToken);
}

internal sealed class GamesMembershipFenceLock
{
    private readonly ConcurrentDictionary<(string ApplicationKey, Guid MembershipId), SemaphoreSlim> _locks = new();

    public async ValueTask<IAsyncDisposable> AcquireAsync(
        string applicationKey,
        Guid membershipId,
        CancellationToken cancellationToken)
    {
        var gate = _locks.GetOrAdd((applicationKey, membershipId), static _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(cancellationToken);
        return new Releaser(gate);
    }

    private sealed class Releaser(SemaphoreSlim gate) : IAsyncDisposable
    {
        public ValueTask DisposeAsync()
        {
            gate.Release();
            return ValueTask.CompletedTask;
        }
    }
}

internal sealed class GamesMembershipWriteFence(
    GamesDbContext dbContext,
    GamesMembershipFenceLock processLock) : IGamesMembershipWriteFence
{
    public async Task<T> ExecuteAsync<T>(
        string applicationKey,
        Guid membershipId,
        Func<Task<T>> operation,
        Func<T> rejected,
        CancellationToken cancellationToken)
    {
        await using var lease = await processLock.AcquireAsync(applicationKey, membershipId, cancellationToken);
        await using var transaction = await BeginSerializableTransactionAsync(cancellationToken);

        if (await IsDeletedAsync(applicationKey, membershipId, cancellationToken))
        {
            if (transaction is not null) await transaction.CommitAsync(cancellationToken);
            return rejected();
        }

        var result = await operation();
        if (transaction is not null) await transaction.CommitAsync(cancellationToken);
        return result;
    }

    public async Task EstablishDeletionBarrierAsync(
        string applicationKey,
        Guid membershipId,
        DateTimeOffset establishedAtUtc,
        CancellationToken cancellationToken)
    {
        await using var lease = await processLock.AcquireAsync(applicationKey, membershipId, cancellationToken);
        await using var transaction = await BeginSerializableTransactionAsync(cancellationToken);

        if (!await IsDeletedAsync(applicationKey, membershipId, cancellationToken))
        {
            dbContext.MembershipDeletionFences.Add(
                new GamesMembershipDeletionFence(applicationKey, membershipId, establishedAtUtc));
            await dbContext.SaveChangesAsync(cancellationToken);
        }

        if (transaction is not null) await transaction.CommitAsync(cancellationToken);
    }

    private Task<bool> IsDeletedAsync(
        string applicationKey,
        Guid membershipId,
        CancellationToken cancellationToken) =>
        dbContext.MembershipDeletionFences
            .AsNoTracking()
            .AnyAsync(
                fence => fence.ApplicationKey == applicationKey && fence.MembershipId == membershipId,
                cancellationToken);

    // On SQL Server the SERIALIZABLE equality read over the composite primary key
    // holds a key-range lock when the row is absent. A concurrent deletion insert
    // therefore waits for the mutation transaction, while a committed tombstone is
    // observed before any later mutation executes. The process lock supplies the
    // same deterministic ordering for the EF in-memory test provider.
    private async Task<Microsoft.EntityFrameworkCore.Storage.IDbContextTransaction?> BeginSerializableTransactionAsync(
        CancellationToken cancellationToken) =>
        dbContext.Database.IsRelational()
            ? await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable, cancellationToken)
            : null;
}
