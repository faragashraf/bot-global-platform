using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Calling.Application;

public sealed record NqrbBlockedAccountEntry(Guid MembershipId, string DisplayName);

public interface INqrbBlockService
{
    Task<IReadOnlyList<NqrbBlockedAccountEntry>> ListAsync(Guid ownerMembershipId, CancellationToken cancellationToken);
    Task<bool> BlockAsync(Guid ownerMembershipId, Guid blockedMembershipId, CancellationToken cancellationToken);
    Task UnblockAsync(Guid ownerMembershipId, Guid blockedMembershipId, CancellationToken cancellationToken);
    Task<bool> IsBlockedAsync(Guid ownerMembershipId, Guid callerMembershipId, CancellationToken cancellationToken);
}

internal sealed class NqrbBlockService(
    CallingDbContext db,
    ICallingAccountDirectory accounts,
    TimeProvider clock) : INqrbBlockService
{
    public async Task<IReadOnlyList<NqrbBlockedAccountEntry>> ListAsync(
        Guid ownerMembershipId, CancellationToken cancellationToken)
    {
        var ids = await db.NqrbBlockedAccounts.AsNoTracking()
            .Where(item => item.ApplicationKey == BotGlobalApplications.Nqrb && item.OwnerMembershipId == ownerMembershipId)
            .OrderByDescending(item => item.CreatedAtUtc)
            .Select(item => item.BlockedMembershipId)
            .ToListAsync(cancellationToken);
        var names = new Dictionary<Guid, string>();
        foreach (var batch in ids.Chunk(100))
        {
            foreach (var account in await accounts.FindActiveNonGuestAsync(BotGlobalApplications.Nqrb, batch, cancellationToken))
                names[account.MembershipId] = account.DisplayName;
        }
        return ids.Select(id => new NqrbBlockedAccountEntry(id, names.GetValueOrDefault(id, "Nqrb account"))).ToArray();
    }

    public async Task<bool> BlockAsync(Guid ownerMembershipId, Guid blockedMembershipId, CancellationToken cancellationToken)
    {
        if (ownerMembershipId == Guid.Empty || blockedMembershipId == Guid.Empty || ownerMembershipId == blockedMembershipId)
            return false;
        var account = await accounts.FindActiveNonGuestAsync(BotGlobalApplications.Nqrb, blockedMembershipId, cancellationToken);
        if (account is null) return false;
        if (!await IsBlockedAsync(ownerMembershipId, blockedMembershipId, cancellationToken))
        {
            var block = new NqrbBlockedAccount(
                BotGlobalApplications.Nqrb, ownerMembershipId, blockedMembershipId, clock.GetUtcNow());
            db.NqrbBlockedAccounts.Add(block);
            try
            {
                await db.SaveChangesAsync(cancellationToken);
            }
            catch (DbUpdateException)
            {
                db.Entry(block).State = EntityState.Detached;
                if (!await IsBlockedAsync(ownerMembershipId, blockedMembershipId, cancellationToken)) throw;
            }
        }
        return true;
    }

    public async Task UnblockAsync(Guid ownerMembershipId, Guid blockedMembershipId, CancellationToken cancellationToken)
    {
        var item = await db.NqrbBlockedAccounts.FindAsync(
            [BotGlobalApplications.Nqrb, ownerMembershipId, blockedMembershipId], cancellationToken);
        if (item is null) return;
        db.NqrbBlockedAccounts.Remove(item);
        await db.SaveChangesAsync(cancellationToken);
    }

    public Task<bool> IsBlockedAsync(Guid ownerMembershipId, Guid callerMembershipId, CancellationToken cancellationToken) =>
        db.NqrbBlockedAccounts.AsNoTracking().AnyAsync(item =>
            item.ApplicationKey == BotGlobalApplications.Nqrb &&
            item.OwnerMembershipId == ownerMembershipId &&
            item.BlockedMembershipId == callerMembershipId, cancellationToken);
}
