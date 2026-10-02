using System.Security.Cryptography;
using System.Text;
using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Calling.Application;

public sealed record NqrbContactBookPage(
    IReadOnlyList<NqrbContactBookEntry> Items,
    int Page,
    int PageSize,
    bool HasMore);

public sealed record NqrbContactBookEntry(
    Guid MembershipId,
    string DisplayName,
    string? Nickname = null);

public enum NqrbContactNicknameStatus { Updated, Unavailable, Invalid }

public sealed record NqrbContactNicknameResult(
    NqrbContactNicknameStatus Status,
    NqrbContactBookEntry? Contact);

public enum NqrbContactAddStatus
{
    Added,
    Existing,
    Unavailable
}

public sealed record NqrbContactAddResult(
    NqrbContactAddStatus Status,
    NqrbContactBookEntry? Contact);

public sealed record NqrbContactInviteCode(
    string Code,
    string ShareLink,
    DateTimeOffset ExpiresAtUtc);

public sealed record NqrbContactInvitePreview(string IssuerDisplayName);

public enum NqrbContactInviteStatus
{
    Created,
    Accepted,
    Invalid,
    Expired,
    Unavailable,
    SelfInvite,
    AlreadyClaimed
}

public sealed record NqrbContactInviteCreateResult(
    NqrbContactInviteStatus Status,
    NqrbContactInviteCode? Invite);

public sealed record NqrbContactInvitePreviewResult(
    NqrbContactInviteStatus Status,
    NqrbContactInvitePreview? Preview);

public sealed record NqrbContactInviteAcceptResult(
    NqrbContactInviteStatus Status,
    NqrbContactBookEntry? Issuer);

public interface INqrbContactBookService
{
    Task<NqrbContactBookEntry?> FindAsync(
        Guid ownerMembershipId,
        Guid contactMembershipId,
        CancellationToken cancellationToken);

    Task<NqrbContactBookPage> ListAsync(
        Guid ownerMembershipId,
        int page,
        int pageSize,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<CallingParticipantDescriptor>> ListCallableContactsAsync(
        Guid ownerMembershipId,
        CancellationToken cancellationToken);

    Task<CallingAccountSearchPage> SearchUsersAsync(
        Guid ownerMembershipId,
        string query,
        int page,
        int pageSize,
        CancellationToken cancellationToken);

    Task<NqrbContactAddResult> AddAsync(
        Guid ownerMembershipId,
        Guid contactMembershipId,
        CancellationToken cancellationToken);

    Task<NqrbContactAddResult> AddFromCallHistoryAsync(
        Guid ownerMembershipId,
        Guid callId,
        CancellationToken cancellationToken);

    Task RemoveAsync(
        Guid ownerMembershipId,
        Guid contactMembershipId,
        CancellationToken cancellationToken);

    Task<NqrbContactNicknameResult> UpdateNicknameAsync(
        Guid ownerMembershipId,
        Guid contactMembershipId,
        string? nickname,
        CancellationToken cancellationToken);

    Task<NqrbContactInviteCreateResult> CreateInviteAsync(
        Guid issuerMembershipId,
        CancellationToken cancellationToken);

    Task<NqrbContactInvitePreviewResult> PreviewInviteAsync(
        Guid recipientMembershipId,
        string code,
        CancellationToken cancellationToken);

    Task<NqrbContactInviteAcceptResult> AcceptInviteAsync(
        Guid recipientMembershipId,
        string code,
        CancellationToken cancellationToken);
}

internal sealed class NqrbContactBookService(
    CallingDbContext db,
    ICallingAccountDirectory accounts,
    TimeProvider clock) : INqrbContactBookService
{
    private const int DefaultPageSize = 20;
    private const int MaximumPageSize = 50;
    private const int ContactLookupBatchSize = 100;
    private const int MaximumQueryLength = 120;
    private const int InviteRandomByteCount = 15;
    private const int InviteCodePayloadLength = 24;
    private const string InviteCodePrefix = "NQ";
    private const string InviteAlphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static readonly TimeSpan InviteLifetime = TimeSpan.FromHours(24);

    public async Task<NqrbContactBookEntry?> FindAsync(
        Guid ownerMembershipId,
        Guid contactMembershipId,
        CancellationToken cancellationToken)
    {
        if (ownerMembershipId == Guid.Empty || contactMembershipId == Guid.Empty)
            return null;
        var edge = await BaseOwnerQuery(ownerMembershipId)
            .SingleOrDefaultAsync(item => item.ContactMembershipId == contactMembershipId, cancellationToken);
        if (edge is null)
            return null;
        var contact = await accounts.FindActiveNonGuestAsync(
            BotGlobalApplications.Nqrb, contactMembershipId, cancellationToken);
        return contact is null
            ? null
            : new NqrbContactBookEntry(contact.MembershipId, contact.DisplayName, edge.Nickname);
    }

    public async Task<NqrbContactBookPage> ListAsync(
        Guid ownerMembershipId,
        int page,
        int pageSize,
        CancellationToken cancellationToken)
    {
        page = NormalizePage(page);
        pageSize = NormalizePageSize(pageSize);
        var orderedContacts = BaseOwnerQuery(ownerMembershipId)
            .OrderBy(item => item.ContactMembershipId)
            .Select(item => new { item.ContactMembershipId, item.Nickname });
        var firstVisibleIndex = ((long)page - 1) * pageSize;
        var visibleIndex = 0L;
        var offset = 0;
        var items = new List<NqrbContactBookEntry>(pageSize + 1);
        while (items.Count <= pageSize)
        {
            var edges = await orderedContacts
                .Skip(offset)
                .Take(ContactLookupBatchSize)
                .ToArrayAsync(cancellationToken);
            if (edges.Length == 0)
            {
                break;
            }

            var active = await ResolveContactsAsync(edges.Select(item => item.ContactMembershipId).ToArray(), cancellationToken);
            foreach (var edge in edges)
            {
                if (!active.TryGetValue(edge.ContactMembershipId, out var contact))
                {
                    continue;
                }

                if (visibleIndex++ >= firstVisibleIndex)
                {
                    items.Add(new NqrbContactBookEntry(contact.MembershipId, contact.DisplayName, edge.Nickname));
                    if (items.Count > pageSize)
                    {
                        break;
                    }
                }
            }

            if (edges.Length < ContactLookupBatchSize || offset > int.MaxValue - ContactLookupBatchSize)
            {
                break;
            }

            offset += ContactLookupBatchSize;
        }

        return new NqrbContactBookPage(items.Take(pageSize).ToArray(), page, pageSize, items.Count > pageSize);
    }

    public async Task<IReadOnlyList<CallingParticipantDescriptor>> ListCallableContactsAsync(
        Guid ownerMembershipId,
        CancellationToken cancellationToken)
    {
        var contactIds = await BaseOwnerQuery(ownerMembershipId)
            .OrderBy(item => item.ContactMembershipId)
            .Select(item => item.ContactMembershipId)
            .ToListAsync(cancellationToken);
        var contacts = await ResolveContactsAsync(contactIds, cancellationToken);
        return contactIds
            .Where(contacts.ContainsKey)
            .Select(id =>
            {
                var contact = contacts[id];
                return new CallingParticipantDescriptor(
                    contact.MembershipId,
                    BotGlobalApplications.Nqrb,
                    contact.SubjectId,
                    contact.DisplayName,
                    true);
            })
            .ToArray();
    }

    public Task<CallingAccountSearchPage> SearchUsersAsync(
        Guid ownerMembershipId,
        string query,
        int page,
        int pageSize,
        CancellationToken cancellationToken)
    {
        var normalized = NormalizeQuery(query);
        return accounts.SearchActiveNonGuestsAsync(
            BotGlobalApplications.Nqrb,
            ownerMembershipId,
            normalized,
            NormalizePage(page),
            NormalizePageSize(pageSize),
            cancellationToken);
    }

    public async Task<NqrbContactAddResult> AddAsync(
        Guid ownerMembershipId,
        Guid contactMembershipId,
        CancellationToken cancellationToken)
    {
        if (ownerMembershipId == Guid.Empty ||
            contactMembershipId == Guid.Empty ||
            ownerMembershipId == contactMembershipId)
        {
            return new NqrbContactAddResult(NqrbContactAddStatus.Unavailable, null);
        }

        var contact = await accounts.FindActiveNonGuestAsync(
            BotGlobalApplications.Nqrb,
            contactMembershipId,
            cancellationToken);
        if (contact is null)
        {
            return new NqrbContactAddResult(NqrbContactAddStatus.Unavailable, null);
        }

        var existing = await BaseOwnerQuery(ownerMembershipId)
            .SingleOrDefaultAsync(item => item.ContactMembershipId == contactMembershipId, cancellationToken);
        if (existing is not null)
        {
            return new NqrbContactAddResult(
                NqrbContactAddStatus.Existing,
                new NqrbContactBookEntry(contact.MembershipId, contact.DisplayName, existing.Nickname));
        }

        var edge = new NqrbContactEdge(
            BotGlobalApplications.Nqrb,
            ownerMembershipId,
            contactMembershipId,
            clock.GetUtcNow());
        db.NqrbContactEdges.Add(edge);
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return new NqrbContactAddResult(
                NqrbContactAddStatus.Added,
                new NqrbContactBookEntry(contact.MembershipId, contact.DisplayName));
        }
        catch (DbUpdateException)
        {
            db.Entry(edge).State = EntityState.Detached;
            existing = await BaseOwnerQuery(ownerMembershipId)
                .SingleOrDefaultAsync(item => item.ContactMembershipId == contactMembershipId, cancellationToken);
            if (existing is null)
            {
                throw;
            }

            return new NqrbContactAddResult(
                NqrbContactAddStatus.Existing,
                new NqrbContactBookEntry(contact.MembershipId, contact.DisplayName, existing.Nickname));
        }
    }

    public async Task<NqrbContactAddResult> AddFromCallHistoryAsync(
        Guid ownerMembershipId,
        Guid callId,
        CancellationToken cancellationToken)
    {
        if (ownerMembershipId == Guid.Empty || callId == Guid.Empty)
        {
            return new NqrbContactAddResult(NqrbContactAddStatus.Unavailable, null);
        }

        var counterpartMembershipId = await db.Calls.AsNoTracking()
            .Where(call =>
                call.Id == callId &&
                call.ApplicationKey == BotGlobalApplications.Nqrb &&
                !call.IsGuestCall &&
                call.Participants.Any(participant => participant.MembershipId == ownerMembershipId))
            .Select(call => call.Participants
                .Where(participant => participant.MembershipId != ownerMembershipId)
                .OrderBy(participant => participant.Id)
                .Select(participant => participant.MembershipId)
                .FirstOrDefault())
            .SingleOrDefaultAsync(cancellationToken);
        if (counterpartMembershipId == Guid.Empty)
        {
            return new NqrbContactAddResult(NqrbContactAddStatus.Unavailable, null);
        }

        return await AddAsync(ownerMembershipId, counterpartMembershipId, cancellationToken);
    }

    public async Task RemoveAsync(
        Guid ownerMembershipId,
        Guid contactMembershipId,
        CancellationToken cancellationToken)
    {
        var edges = await db.NqrbContactEdges
            .Where(item =>
                item.ApplicationKey == BotGlobalApplications.Nqrb &&
                item.OwnerMembershipId == ownerMembershipId &&
                item.ContactMembershipId == contactMembershipId)
            .ToListAsync(cancellationToken);
        db.NqrbContactEdges.RemoveRange(edges);
        await db.SaveChangesAsync(cancellationToken);
    }

    public async Task<NqrbContactNicknameResult> UpdateNicknameAsync(
        Guid ownerMembershipId,
        Guid contactMembershipId,
        string? nickname,
        CancellationToken cancellationToken)
    {
        if (ownerMembershipId == Guid.Empty || contactMembershipId == Guid.Empty)
            return new NqrbContactNicknameResult(NqrbContactNicknameStatus.Unavailable, null);

        var edge = await db.NqrbContactEdges.SingleOrDefaultAsync(item =>
            item.ApplicationKey == BotGlobalApplications.Nqrb &&
            item.OwnerMembershipId == ownerMembershipId &&
            item.ContactMembershipId == contactMembershipId, cancellationToken);
        if (edge is null)
            return new NqrbContactNicknameResult(NqrbContactNicknameStatus.Unavailable, null);
        if (!edge.SetNickname(nickname))
            return new NqrbContactNicknameResult(NqrbContactNicknameStatus.Invalid, null);

        var contact = await accounts.FindActiveNonGuestAsync(
            BotGlobalApplications.Nqrb, contactMembershipId, cancellationToken);
        if (contact is null)
            return new NqrbContactNicknameResult(NqrbContactNicknameStatus.Unavailable, null);

        await db.SaveChangesAsync(cancellationToken);
        return new NqrbContactNicknameResult(
            NqrbContactNicknameStatus.Updated,
            new NqrbContactBookEntry(contact.MembershipId, contact.DisplayName, edge.Nickname));
    }

    public async Task<NqrbContactInviteCreateResult> CreateInviteAsync(
        Guid issuerMembershipId,
        CancellationToken cancellationToken)
    {
        if (issuerMembershipId == Guid.Empty ||
            await accounts.FindActiveNonGuestAsync(
                BotGlobalApplications.Nqrb,
                issuerMembershipId,
                cancellationToken) is null)
        {
            return new NqrbContactInviteCreateResult(NqrbContactInviteStatus.Unavailable, null);
        }

        var now = clock.GetUtcNow();
        var expires = now.Add(InviteLifetime);
        for (var attempt = 0; attempt < 4; attempt++)
        {
            var code = GenerateInviteCode();
            var invite = new NqrbContactInvite(
                BotGlobalApplications.Nqrb,
                HashInviteCode(code),
                issuerMembershipId,
                now,
                expires);
            db.NqrbContactInvites.Add(invite);
            try
            {
                await db.SaveChangesAsync(cancellationToken);
                return new NqrbContactInviteCreateResult(
                    NqrbContactInviteStatus.Created,
                    new NqrbContactInviteCode(
                        code,
                        $"nqrb://invite/{Uri.EscapeDataString(code)}",
                        expires));
            }
            catch (DbUpdateException)
            {
                db.Entry(invite).State = EntityState.Detached;
            }
        }

        throw new InvalidOperationException("Unable to allocate a unique NQRB contact invite code.");
    }

    public async Task<NqrbContactInvitePreviewResult> PreviewInviteAsync(
        Guid recipientMembershipId,
        string code,
        CancellationToken cancellationToken)
    {
        var invite = await FindUsableInviteAsync(code, cancellationToken);
        if (invite.Status != NqrbContactInviteStatus.Created || invite.Invite is null)
        {
            return new NqrbContactInvitePreviewResult(invite.Status, null);
        }

        if (recipientMembershipId == invite.Invite.IssuerMembershipId)
        {
            return new NqrbContactInvitePreviewResult(NqrbContactInviteStatus.SelfInvite, null);
        }

        var recipient = await accounts.FindActiveNonGuestAsync(
            BotGlobalApplications.Nqrb,
            recipientMembershipId,
            cancellationToken);
        var issuer = await accounts.FindActiveNonGuestAsync(
            BotGlobalApplications.Nqrb,
            invite.Invite.IssuerMembershipId,
            cancellationToken);
        if (issuer is null || recipient is null)
        {
            return new NqrbContactInvitePreviewResult(NqrbContactInviteStatus.Unavailable, null);
        }

        return new NqrbContactInvitePreviewResult(
            NqrbContactInviteStatus.Created,
            new NqrbContactInvitePreview(issuer.DisplayName));
    }

    public async Task<NqrbContactInviteAcceptResult> AcceptInviteAsync(
        Guid recipientMembershipId,
        string code,
        CancellationToken cancellationToken)
    {
        if (recipientMembershipId == Guid.Empty)
        {
            return new NqrbContactInviteAcceptResult(NqrbContactInviteStatus.Unavailable, null);
        }

        var normalized = NormalizeInviteCode(code);
        if (normalized is null)
        {
            return new NqrbContactInviteAcceptResult(NqrbContactInviteStatus.Invalid, null);
        }

        var hash = HashInviteCode(normalized);
        var now = clock.GetUtcNow();
        await using var transaction = await BeginTransactionIfSupportedAsync(cancellationToken);
        var invite = await db.NqrbContactInvites
            .AsNoTracking()
            .SingleOrDefaultAsync(
                item =>
                    item.ApplicationKey == BotGlobalApplications.Nqrb &&
                    item.CodeHash == hash,
                cancellationToken);
        if (invite is null)
        {
            return new NqrbContactInviteAcceptResult(NqrbContactInviteStatus.Invalid, null);
        }

        if (invite.ExpiresAtUtc <= now)
        {
            return new NqrbContactInviteAcceptResult(NqrbContactInviteStatus.Expired, null);
        }

        if (invite.ClaimedAtUtc is not null)
        {
            return new NqrbContactInviteAcceptResult(NqrbContactInviteStatus.AlreadyClaimed, null);
        }

        if (invite.IssuerMembershipId == recipientMembershipId)
        {
            return new NqrbContactInviteAcceptResult(NqrbContactInviteStatus.SelfInvite, null);
        }

        var issuer = await accounts.FindActiveNonGuestAsync(
            BotGlobalApplications.Nqrb,
            invite.IssuerMembershipId,
            cancellationToken);
        var recipient = await accounts.FindActiveNonGuestAsync(
            BotGlobalApplications.Nqrb,
            recipientMembershipId,
            cancellationToken);
        if (issuer is null || recipient is null)
        {
            return new NqrbContactInviteAcceptResult(NqrbContactInviteStatus.Unavailable, null);
        }

        var claimed = await db.NqrbContactInvites
            .Where(item =>
                item.ApplicationKey == BotGlobalApplications.Nqrb &&
                item.CodeHash == hash &&
                item.ClaimedAtUtc == null)
            .ExecuteUpdateAsync(
                setters => setters
                    .SetProperty(item => item.ClaimedAtUtc, (DateTimeOffset?)now)
                    .SetProperty(item => item.ClaimedByMembershipId, (Guid?)recipientMembershipId),
                cancellationToken);
        if (claimed != 1)
        {
            return new NqrbContactInviteAcceptResult(NqrbContactInviteStatus.AlreadyClaimed, null);
        }

        if (invite.ExpiresAtUtc <= clock.GetUtcNow())
        {
            return new NqrbContactInviteAcceptResult(NqrbContactInviteStatus.Expired, null);
        }

        await EnsureContactEdgeAsync(invite.IssuerMembershipId, recipientMembershipId, now, cancellationToken);
        await EnsureContactEdgeAsync(recipientMembershipId, invite.IssuerMembershipId, now, cancellationToken);
        if (transaction is not null)
        {
            await transaction.CommitAsync(cancellationToken);
        }

        return new NqrbContactInviteAcceptResult(
            NqrbContactInviteStatus.Accepted,
            new NqrbContactBookEntry(issuer.MembershipId, issuer.DisplayName));
    }

    private IQueryable<NqrbContactEdge> BaseOwnerQuery(Guid ownerMembershipId) =>
        db.NqrbContactEdges.AsNoTracking().Where(item =>
            item.ApplicationKey == BotGlobalApplications.Nqrb &&
            item.OwnerMembershipId == ownerMembershipId);

    private async Task EnsureContactEdgeAsync(
        Guid ownerMembershipId,
        Guid contactMembershipId,
        DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        var exists = await db.NqrbContactEdges.AnyAsync(
            item =>
                item.ApplicationKey == BotGlobalApplications.Nqrb &&
                item.OwnerMembershipId == ownerMembershipId &&
                item.ContactMembershipId == contactMembershipId,
            cancellationToken);
        if (exists)
        {
            return;
        }

        var edge = new NqrbContactEdge(
            BotGlobalApplications.Nqrb,
            ownerMembershipId,
            contactMembershipId,
            now);
        db.NqrbContactEdges.Add(edge);
        try
        {
            await db.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateException)
        {
            db.Entry(edge).State = EntityState.Detached;
            var insertedByAnotherRequest = await db.NqrbContactEdges.AsNoTracking().AnyAsync(
                item =>
                    item.ApplicationKey == BotGlobalApplications.Nqrb &&
                    item.OwnerMembershipId == ownerMembershipId &&
                    item.ContactMembershipId == contactMembershipId,
                cancellationToken);
            if (!insertedByAnotherRequest)
            {
                throw;
            }
        }
    }

    private async Task<(NqrbContactInviteStatus Status, NqrbContactInvite? Invite)> FindUsableInviteAsync(
        string code,
        CancellationToken cancellationToken)
    {
        var normalized = NormalizeInviteCode(code);
        if (normalized is null)
        {
            return (NqrbContactInviteStatus.Invalid, null);
        }

        var invite = await db.NqrbContactInvites.AsNoTracking()
            .SingleOrDefaultAsync(
                item =>
                    item.ApplicationKey == BotGlobalApplications.Nqrb &&
                    item.CodeHash == HashInviteCode(normalized),
                cancellationToken);
        if (invite is null)
        {
            return (NqrbContactInviteStatus.Invalid, null);
        }

        if (invite.ExpiresAtUtc <= clock.GetUtcNow())
        {
            return (NqrbContactInviteStatus.Expired, null);
        }

        return invite.ClaimedAtUtc is null
            ? (NqrbContactInviteStatus.Created, invite)
            : (NqrbContactInviteStatus.AlreadyClaimed, null);
    }

    private async Task<IDbContextTransaction?> BeginTransactionIfSupportedAsync(CancellationToken cancellationToken)
    {
        if (db.Database.ProviderName?.Contains("InMemory", StringComparison.OrdinalIgnoreCase) == true)
        {
            return null;
        }

        return await db.Database.BeginTransactionAsync(cancellationToken);
    }

    private async Task<Dictionary<Guid, CallingAccountDescriptor>> ResolveContactsAsync(
        IReadOnlyCollection<Guid> contactIds,
        CancellationToken cancellationToken)
    {
        if (contactIds.Count == 0)
        {
            return [];
        }

        var contacts = await accounts.FindActiveNonGuestAsync(
            BotGlobalApplications.Nqrb,
            contactIds,
            cancellationToken);
        return contacts.ToDictionary(item => item.MembershipId);
    }

    private static int NormalizePage(int page) => Math.Max(1, page);

    private static int NormalizePageSize(int pageSize) =>
        Math.Clamp(pageSize <= 0 ? DefaultPageSize : pageSize, 1, MaximumPageSize);

    private static string NormalizeQuery(string query)
    {
        var normalized = query.Trim();
        if (normalized.Length < 2 || normalized.Length > MaximumQueryLength)
        {
            throw new ArgumentException("nqrb_user_search_query_invalid", nameof(query));
        }

        return normalized;
    }

    private static string GenerateInviteCode()
    {
        Span<byte> bytes = stackalloc byte[InviteRandomByteCount];
        RandomNumberGenerator.Fill(bytes);
        var builder = new StringBuilder(InviteCodePrefix, InviteCodePrefix.Length + 1 + InviteCodePayloadLength + 5);
        var buffer = 0;
        var bits = 0;
        var written = 0;
        foreach (var value in bytes)
        {
            buffer = (buffer << 8) | value;
            bits += 8;
            while (bits >= 5 && written < InviteCodePayloadLength)
            {
                bits -= 5;
                if (written % 4 == 0)
                {
                    builder.Append('-');
                }

                builder.Append(InviteAlphabet[(buffer >> bits) & 31]);
                written++;
            }
        }

        return builder.ToString();
    }

    private static string? NormalizeInviteCode(string code)
    {
        var builder = new StringBuilder(InviteCodePrefix.Length + InviteCodePayloadLength);
        foreach (var character in code.Trim())
        {
            if (character is '-' or '_' or ' ')
            {
                continue;
            }

            builder.Append(char.ToUpperInvariant(character));
        }

        var normalized = builder.ToString();
        if (!normalized.StartsWith(InviteCodePrefix, StringComparison.Ordinal) ||
            normalized.Length != InviteCodePrefix.Length + InviteCodePayloadLength ||
            normalized.Skip(InviteCodePrefix.Length).Any(item => !InviteAlphabet.Contains(item)))
        {
            return null;
        }

        return normalized;
    }

    private static string HashInviteCode(string code)
    {
        var normalized = NormalizeInviteCode(code)
            ?? throw new ArgumentException("Invite code is malformed.", nameof(code));
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(normalized));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }
}
