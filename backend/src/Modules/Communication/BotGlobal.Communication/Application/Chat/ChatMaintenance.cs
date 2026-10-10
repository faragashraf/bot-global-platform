using BotGlobal.Communication.Domain.Chat;
using BotGlobal.Communication.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Communication.Application.Chat;

internal sealed class ChatVoiceSweeper(CommunicationDbContext db, IChatVoiceStorage storage, TimeProvider clock)
{
    public async Task SweepAsync(CancellationToken token)
    {
        var now = clock.GetUtcNow();
        var rows = await db.ChatVoiceTransfers.Where(x => x.State == ChatVoiceTransferState.Available ||
            x.State == ChatVoiceTransferState.AcknowledgedDeletionPending || x.State == ChatVoiceTransferState.ExpiredDeletionPending).ToListAsync(token);
        foreach (var row in rows) row.Expire(now);
        // Terminal intent is durable before deleting bytes, including on retry/restart.
        await db.SaveChangesAsync(token);
        foreach (var row in rows)
        {
            if (row.State is not (ChatVoiceTransferState.AcknowledgedDeletionPending or ChatVoiceTransferState.ExpiredDeletionPending)) continue;
            try { if (!storage.Delete(row.FileKey)) throw new IOException(); row.MarkDeleted(now); }
            catch { row.MarkDeleteFailure("chat_voice_delete_failed"); }
        }
        await db.SaveChangesAsync(token);
        var referenced = (await db.ChatVoiceTransfers.AsNoTracking().Select(x => x.FileKey).ToListAsync(token)).ToHashSet(StringComparer.Ordinal);
        foreach (var key in storage.EnumerateFileKeys())
        {
            var written = storage.LastWriteTimeUtc(key);
            if (!referenced.Contains(key) && written.HasValue && written.Value < now - TimeSpan.FromHours(1))
            {
                // The DB query must have succeeded; uncertainty never authorizes deletion.
                try { storage.Delete(key); } catch { }
            }
        }
    }
}

internal sealed class ChatMaintenanceBackgroundService(IServiceScopeFactory scopes, IOptions<ChatVoiceOptions> options,
    ILogger<ChatMaintenanceBackgroundService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(Math.Clamp(options.Value.SweepMinutes, 1, 60)));
        while (!stoppingToken.IsCancellationRequested)
        {
            try { await using var scope = scopes.CreateAsyncScope(); await scope.ServiceProvider.GetRequiredService<ChatVoiceSweeper>().SweepAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception error) { logger.LogWarning("Chat maintenance failed. ErrorType={ErrorType}", error.GetType().Name); }
            if (!await timer.WaitForNextTickAsync(stoppingToken)) break;
        }
    }
}
