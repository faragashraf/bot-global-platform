using System.Data;
using BotGlobal.Communication.Domain.Chat;
using BotGlobal.Communication.Infrastructure.Persistence;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Communication.Hubs;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.SignalR;

namespace BotGlobal.Communication.Application.Chat;

internal sealed class ChatEngine(
    CommunicationDbContext db,
    IHttpContextAccessor httpContext,
    IChatActorResolver actorResolver,
    ChatPolicyRegistry registry,
    IChatVoiceStorage voiceStorage,
    IMobileRecipientResolver recipients,
    IOptions<ChatVoiceOptions> voiceOptions,
    TimeProvider clock,
    ChatConnectionRegistry? connections = null,
    IHubContext<ChatHub>? hub = null,
    IServiceProvider? services = null) : IChatEngine
{
    public async Task<ChatConversationView?> CreateOrGetDirectAsync(string reference, CancellationToken cancellationToken)
    {
        var context = await RequireContextAsync(cancellationToken);
        if (context is null || string.IsNullOrWhiteSpace(reference) || reference.Trim().Length > ChatLimits.Subject) return null;
        var (actor, directory, policy) = context.Value;
        var self = await directory.FindBySubjectAsync(actor.Application, actor.SubjectId, cancellationToken);
        var counterpart = await directory.FindByReferenceAsync(actor.Application, reference.Trim(), cancellationToken);
        if (self is null || counterpart is null || string.Equals(actor.SubjectId, counterpart.SubjectId, StringComparison.Ordinal) ||
            !await policy.CanStartDirectConversationAsync(actor, self, counterpart, cancellationToken)) return null;

        var pairKey = ChatConversation.CreatePairKey(actor.SubjectId, counterpart.SubjectId);
        var existing = await db.ChatConversations.SingleOrDefaultAsync(x =>
            x.ApplicationId == actor.Application.ApplicationId && x.DirectPairKey == pairKey, cancellationToken);
        if (existing is null)
        {
            existing = new ChatConversation(actor.Application.ApplicationId, actor.SubjectId, counterpart.SubjectId, clock.GetUtcNow());
            db.ChatConversations.Add(existing);
            try { await db.SaveChangesAsync(cancellationToken); }
            catch (DbUpdateException)
            {
                db.ChangeTracker.Clear();
                existing = await db.ChatConversations.SingleOrDefaultAsync(x =>
                    x.ApplicationId == actor.Application.ApplicationId && x.DirectPairKey == pairKey, cancellationToken);
                if (existing is null) throw;
            }
        }
        return await ToConversationViewAsync(existing, actor, directory, cancellationToken);
    }

    public async Task<ChatPage<ChatConversationView>> ListConversationsAsync(DateTimeOffset? before, int take, CancellationToken cancellationToken, string? cursor = null)
    {
        var context = await RequireContextAsync(cancellationToken);
        if (context is null) return new ChatPage<ChatConversationView>([], false);
        var (actor, directory, _) = context.Value;
        take = Math.Clamp(take, 1, 50);
        var query = db.ChatConversations.AsNoTracking().Where(x => x.ApplicationId == actor.Application.ApplicationId &&
            (x.FirstSubjectId == actor.SubjectId || x.SecondSubjectId == actor.SubjectId));
        if (cursor is not null)
        {
            var parts = cursor.Split('|');
            if (parts.Length != 2 || !long.TryParse(parts[0], out var ticks) || ticks < DateTimeOffset.MinValue.Ticks ||
                ticks > DateTimeOffset.MaxValue.Ticks || !Guid.TryParse(parts[1], out var id)) return new([], false);
            var time = new DateTimeOffset(ticks, TimeSpan.Zero);
            query = query.Where(x => x.UpdatedAtUtc < time || (x.UpdatedAtUtc == time && x.Id.CompareTo(id) < 0));
        }
        else if (before.HasValue) query = query.Where(x => x.UpdatedAtUtc < before.Value);
        var rows = await query.OrderByDescending(x => x.UpdatedAtUtc).ThenByDescending(x => x.Id).Take(take + 1).ToListAsync(cancellationToken);
        var views = new List<ChatConversationView>();
        foreach (var row in rows.Take(take)) views.Add(await ToConversationViewAsync(row, actor, directory, cancellationToken));
        var last = rows.Take(take).LastOrDefault();
        return new ChatPage<ChatConversationView>(views, rows.Count > take, NextConversationCursor:
            rows.Count > take && last is not null ? $"{last.UpdatedAtUtc.UtcTicks}|{last.Id:D}" : null);
    }

    public async Task<ChatPage<ChatMessageView>?> ListMessagesAsync(Guid conversationId, long afterSequence, int take, CancellationToken cancellationToken)
    {
        var context = await RequireContextAsync(cancellationToken);
        if (context is null || conversationId == Guid.Empty || afterSequence < 0) return null;
        var actor = context.Value.Actor;
        var conversation = await OwnedConversationAsync(actor, conversationId, true, cancellationToken);
        if (conversation is null) return null;
        take = Math.Clamp(take, 1, 100);
        var rows = await db.ChatMessages.AsNoTracking().Where(x => x.ApplicationId == actor.Application.ApplicationId &&
                x.ConversationId == conversationId && x.Sequence > afterSequence)
            .OrderBy(x => x.Sequence).Take(take + 1).ToListAsync(cancellationToken);
        return new ChatPage<ChatMessageView>(await ToMessageViewsAsync(rows.Take(take).ToArray(), cancellationToken),
            rows.Count > take, rows.Take(take).LastOrDefault()?.Sequence);
    }

    public Task<ChatSendResult> SendTextAsync(Guid conversationId, string clientMessageId, string text, CancellationToken cancellationToken) =>
        SendTextAsync(conversationId, clientMessageId, text, null, cancellationToken);

    public async Task<ChatSendResult> SendTextAsync(Guid conversationId, string clientMessageId, string text,
        Guid? replyToMessageId, CancellationToken cancellationToken)
    {
        var normalized = text?.Trim() ?? string.Empty;
        if (normalized.Length is 0 or > ChatLimits.Text) return new ChatSendResult(null, Conflict: true);
        var fingerprint = ChatMessage.FingerprintText(normalized, replyToMessageId);
        return await SendAsync(conversationId, clientMessageId, fingerprint, ChatMessageKind.Text, normalized, null,
            replyToMessageId, cancellationToken);
    }

    public async Task<ChatSendResult> SendVoiceAsync(Guid conversationId, string clientMessageId, Stream content,
        string contentType, int durationMilliseconds, CancellationToken cancellationToken)
    {
        if (durationMilliseconds is <= 0 or > 300_000) return new ChatSendResult(null, Conflict: true);
        // Authenticate and check the target before accepting up to 10 MiB of input.
        if (await AuthorizedSendConversationAsync(conversationId, cancellationToken) is null)
            return new ChatSendResult(null, Forbidden: true);
        ChatMessage.NormalizeClientId(clientMessageId);
        var upload = await voiceStorage.PublishAsync(content, contentType, cancellationToken);
        var transferId = Guid.NewGuid();
        var measuredDuration = upload.DurationMilliseconds;
        if (measuredDuration is <= 0 or > 300_000) { voiceStorage.Delete(upload.FileKey); return new(null, Conflict: true); }
        var fingerprint = ChatMessage.FingerprintVoice(upload.Sha256, upload.Length, measuredDuration);
        try
        {
            var result = await SendAsync(conversationId, clientMessageId, fingerprint, ChatMessageKind.Voice, null,
                (transferId, upload, measuredDuration), null, cancellationToken);
            if (result.Message?.VoiceTransferId != transferId) voiceStorage.Delete(upload.FileKey);
            return result;
        }
        catch
        {
            // Commit may have succeeded despite a cancellation/transport/post-commit query error.
            // Never delete on an uncertain persistence result. The age-bounded orphan sweep
            // reconciles against durable references after recovery.
            throw;
        }
    }

    private async Task<ChatSendResult> SendAsync(Guid conversationId, string clientMessageId, string fingerprint,
        ChatMessageKind kind, string? text, (Guid Id, ChatVoiceUpload Upload, int Duration)? voice,
        Guid? replyToMessageId, CancellationToken cancellationToken)
    {
        ChatMessage.NormalizeClientId(clientMessageId);
        for (var attempt = 0; attempt < 3; attempt++)
        {
            var context = await RequireContextAsync(cancellationToken);
            if (context is null) return new ChatSendResult(null, Forbidden: true);
            var (actor, directory, policy) = context.Value;
            var authorized = await AuthorizedSendConversationAsync(conversationId, cancellationToken);
            if (authorized is null) return new(null, Forbidden: true);
            var existing = await db.ChatMessages.AsNoTracking().SingleOrDefaultAsync(x =>
                x.ApplicationId == actor.Application.ApplicationId && x.SenderSubjectId == actor.SubjectId &&
                x.ClientMessageId == clientMessageId.Trim(), cancellationToken);
            if (existing is not null)
                return existing.ConversationId == conversationId && string.Equals(existing.PayloadFingerprint, fingerprint, StringComparison.Ordinal)
                    ? new ChatSendResult((await ToMessageViewsAsync([existing], cancellationToken))[0])
                    : new ChatSendResult(null, Conflict: true);

            await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable, cancellationToken);
            try
            {
                var conversation = await OwnedConversationAsync(actor, conversationId, false, cancellationToken);
                if (conversation is null) return new ChatSendResult(null, Forbidden: true);
                var counterpartSubject = conversation.Counterpart(actor.SubjectId);
                var self = await directory.FindBySubjectAsync(actor.Application, actor.SubjectId, cancellationToken);
                var counterpart = await directory.FindBySubjectAsync(actor.Application, counterpartSubject, cancellationToken);
                if (self is null || counterpart is null || !await policy.CanStartDirectConversationAsync(actor, self, counterpart, cancellationToken))
                    return new ChatSendResult(null, Forbidden: true);
                var reply = replyToMessageId.HasValue
                    ? await ReplySnapshotAsync(actor.Application.ApplicationId, conversation.Id, replyToMessageId.Value, cancellationToken)
                    : null;
                if (replyToMessageId.HasValue && reply is null) return new ChatSendResult(null, Conflict: true);
                var now = clock.GetUtcNow();
                var message = new ChatMessage(actor.Application.ApplicationId, conversation.Id,
                    conversation.AllocateSequence(now), actor.SubjectId, clientMessageId, kind, fingerprint, now, text, voice?.Id,
                    reply?.MessageId, reply?.SenderSubjectId, reply?.Kind, reply?.Text, reply?.VoiceDurationMilliseconds);
                if (voice.HasValue)
                {
                    var retention = TimeSpan.FromDays(Math.Clamp(voiceOptions.Value.PublishedRetentionDays, 1, 7));
                    var transfer = new ChatVoiceTransfer(voice.Value.Id, actor.Application.ApplicationId, conversation.Id,
                        actor.SubjectId, counterpartSubject, voice.Value.Upload.FileKey, voice.Value.Upload.Sha256,
                        voice.Value.Upload.Length, voice.Value.Duration, now, now + retention);
                    transfer.AttachMessage(message.Id);
                    db.ChatVoiceTransfers.Add(transfer);
                }
                db.ChatMessages.Add(message);
                db.ChatDispatches.Add(new ChatDispatch(actor.Application.ApplicationId, message.Id, counterpartSubject, now));
                await db.SaveChangesAsync(cancellationToken);
                await transaction.CommitAsync(cancellationToken);
                return new ChatSendResult((await ToMessageViewsAsync([message], cancellationToken))[0]);
            }
            catch (DbUpdateConcurrencyException) when (attempt < 2)
            {
                await transaction.RollbackAsync(cancellationToken); db.ChangeTracker.Clear();
            }
            catch (DbUpdateException) when (attempt < 2)
            {
                await transaction.RollbackAsync(cancellationToken); db.ChangeTracker.Clear();
            }
        }
        db.ChangeTracker.Clear();
        var finalActor = await RequireActorAsync(cancellationToken);
        if (finalActor is null) return new ChatSendResult(null, Forbidden: true);
        var raced = await db.ChatMessages.AsNoTracking().SingleOrDefaultAsync(x => x.ApplicationId == finalActor.Application.ApplicationId &&
            x.SenderSubjectId == finalActor.SubjectId && x.ClientMessageId == clientMessageId.Trim(), cancellationToken);
        return raced is not null && raced.ConversationId == conversationId && string.Equals(raced.PayloadFingerprint, fingerprint, StringComparison.Ordinal)
            ? new ChatSendResult((await ToMessageViewsAsync([raced], cancellationToken))[0])
            : new ChatSendResult(null, Conflict: true);
    }

    public async Task<(ChatVoiceTransfer Transfer, Stream Content)?> DownloadVoiceAsync(Guid transferId, CancellationToken cancellationToken)
    {
        var context = await RequireContextAsync(cancellationToken);
        if (context is null) return null;
        var (actor, _, policy) = context.Value;
        var transfer = await db.ChatVoiceTransfers.AsNoTracking().SingleOrDefaultAsync(x =>
            x.ApplicationId == actor.Application.ApplicationId && x.Id == transferId && x.RecipientSubjectId == actor.SubjectId, cancellationToken);
        if (transfer is null || !transfer.IsDownloadable(clock.GetUtcNow())) return null;
        var sender = await context.Value.Directory.FindBySubjectAsync(actor.Application, transfer.SenderSubjectId, cancellationToken);
        var recipient = await context.Value.Directory.FindBySubjectAsync(actor.Application, transfer.RecipientSubjectId, cancellationToken);
        if (sender is null || recipient is null || await policy.IsBidirectionallyBlockedAsync(actor.Application, sender, recipient, cancellationToken)) return null;
        try { return (transfer, voiceStorage.OpenRead(transfer.FileKey)); } catch (FileNotFoundException) { return null; }
    }

    public async Task<bool> AcknowledgeVoiceAsync(Guid transferId, Guid installationId, string sha256, long length, CancellationToken cancellationToken)
    {
        var actor = (await RequireContextAsync(cancellationToken))?.Actor;
        if (actor is null || installationId == Guid.Empty || sha256?.Length != 64 || length <= 0) return false;
        var transfer = await db.ChatVoiceTransfers.SingleOrDefaultAsync(x => x.ApplicationId == actor.Application.ApplicationId &&
            x.Id == transferId && x.RecipientSubjectId == actor.SubjectId, cancellationToken);
        if (transfer is null) return false;
        if (actor.Mechanism == ChatActorMechanism.PairedDevice && actor.InstallationId != installationId) return false;
        var activeDevices = await recipients.ResolveActiveDevicesAsync(new NotificationApplicationContext(actor.Application.ApplicationId), actor.SubjectId, cancellationToken);
        if (activeDevices.All(x => x.DeviceId != installationId)) return false;
        if (!transfer.Acknowledge(actor.SubjectId, installationId, sha256.ToLowerInvariant(), length, clock.GetUtcNow())) return false;
        await db.SaveChangesAsync(cancellationToken);
        if (transfer.State == ChatVoiceTransferState.AcknowledgedDeletionPending)
            await TryDeleteAsync(transfer, cancellationToken);
        return true;
    }

    public async Task<long?> AdvanceReadReceiptAsync(Guid conversationId, long sequence, CancellationToken cancellationToken)
    {
        var actor = (await RequireContextAsync(cancellationToken))?.Actor;
        if (actor is null || sequence < 0) return null;
        var conversation = await OwnedConversationAsync(actor, conversationId, false, cancellationToken);
        if (conversation is null || sequence > conversation.NextSequence) return null;
        var receipts = db.ChatReceipts.Where(x => x.ApplicationId == actor.Application.ApplicationId &&
            x.ConversationId == conversationId && x.SubjectId == actor.SubjectId);
        // Predicate and max are evaluated atomically by the database, not on stale tracked entities.
        for (var attempt = 0; attempt < 3; attempt++)
        {
            var now = clock.GetUtcNow();
            await receipts.Where(x => x.LastReadSequence < sequence).ExecuteUpdateAsync(setters => setters
                .SetProperty(x => x.LastReadSequence, sequence).SetProperty(x => x.UpdatedAtUtc, now), cancellationToken);
            var existing = await receipts.AsNoTracking().Select(x => (long?)x.LastReadSequence).SingleOrDefaultAsync(cancellationToken);
            if (existing.HasValue)
            {
                await NotifyReadReceiptAsync(actor, conversation, existing.Value, now, cancellationToken);
                return existing.Value;
            }
            var added = new ChatReceipt(actor.Application.ApplicationId, conversationId, actor.SubjectId, sequence, now);
            db.ChatReceipts.Add(added);
            try
            {
                await db.SaveChangesAsync(cancellationToken);
                await NotifyReadReceiptAsync(actor, conversation, sequence, now, cancellationToken);
                return sequence;
            }
            catch (DbUpdateException) when (attempt < 2) { db.Entry(added).State = EntityState.Detached; }
        }
        return null;
    }

    private async Task NotifyReadReceiptAsync(ChatActor actor, ChatConversation conversation, long sequence, DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        if (sequence <= 0 || connections is null || hub is null || services is null) return;
        var hint = new ChatMessageHint(actor.Application.ApplicationId, conversation.Id, Guid.Empty, sequence, "read", now);
        await connections.SendAsync(hint, conversation.Counterpart(actor.SubjectId), services, hub, cancellationToken);
    }

    private async Task TryDeleteAsync(ChatVoiceTransfer transfer, CancellationToken cancellationToken)
    {
        try
        {
            if (!voiceStorage.Delete(transfer.FileKey)) throw new IOException();
            transfer.MarkDeleted(clock.GetUtcNow());
        }
        catch { transfer.MarkDeleteFailure("chat_voice_delete_failed"); }
        await db.SaveChangesAsync(cancellationToken);
    }

    private async Task<ChatConversation?> OwnedConversationAsync(ChatActor actor, Guid id, bool noTracking, CancellationToken token)
    {
        var query = noTracking ? db.ChatConversations.AsNoTracking() : db.ChatConversations;
        return await query.SingleOrDefaultAsync(x => x.ApplicationId == actor.Application.ApplicationId && x.Id == id &&
            (x.FirstSubjectId == actor.SubjectId || x.SecondSubjectId == actor.SubjectId), token);
    }

    private async Task<ChatConversation?> AuthorizedSendConversationAsync(Guid id, CancellationToken token)
    {
        var context = await RequireContextAsync(token);
        if (context is null) return null;
        var (actor, directory, policy) = context.Value;
        var conversation = await OwnedConversationAsync(actor, id, true, token);
        if (conversation is null) return null;
        var self = await directory.FindBySubjectAsync(actor.Application, actor.SubjectId, token);
        var other = await directory.FindBySubjectAsync(actor.Application, conversation.Counterpart(actor.SubjectId), token);
        return self is not null && other is not null && await policy.CanStartDirectConversationAsync(actor, self, other, token)
            ? conversation : null;
    }

    private async Task<ChatConversationView> ToConversationViewAsync(ChatConversation row, ChatActor actor,
        IChatParticipantDirectory directory, CancellationToken token)
    {
        var counterpartSubject = row.Counterpart(actor.SubjectId);
        var counterpart = await directory.FindBySubjectAsync(actor.Application, counterpartSubject, token);
        var receipt = await db.ChatReceipts.AsNoTracking().Where(x => x.ApplicationId == actor.Application.ApplicationId &&
            x.ConversationId == row.Id && x.SubjectId == actor.SubjectId).Select(x => (long?)x.LastReadSequence).SingleOrDefaultAsync(token) ?? 0;
        var counterpartReceipt = await db.ChatReceipts.AsNoTracking().Where(x => x.ApplicationId == actor.Application.ApplicationId &&
            x.ConversationId == row.Id && x.SubjectId == counterpartSubject).Select(x => (long?)x.LastReadSequence).SingleOrDefaultAsync(token) ?? 0;
        return new ChatConversationView(row.Id, counterpartSubject, counterpart?.Reference,
            counterpart?.DisplayName, row.NextSequence, receipt, counterpartReceipt, row.UpdatedAtUtc);
    }

    private async Task<IReadOnlyList<ChatMessageView>> ToMessageViewsAsync(IReadOnlyCollection<ChatMessage> rows, CancellationToken token)
    {
        var applicationIds = rows.Select(x => x.ApplicationId).Distinct().ToArray();
        var transferIds = rows.Where(x => x.VoiceTransferId.HasValue).Select(x => x.VoiceTransferId!.Value).ToArray();
        var transfers = transferIds.Length == 0 ? new Dictionary<Guid, ChatVoiceTransfer>() :
            await db.ChatVoiceTransfers.AsNoTracking().Where(x => applicationIds.Contains(x.ApplicationId) && transferIds.Contains(x.Id)).ToDictionaryAsync(x => x.Id, token);
        var messageIds = rows.Select(x => x.Id).ToArray();
        var dispatches = messageIds.Length == 0 ? new Dictionary<Guid, ChatDispatchState>() :
            await db.ChatDispatches.AsNoTracking().Where(x => applicationIds.Contains(x.ApplicationId) && messageIds.Contains(x.MessageId))
                .ToDictionaryAsync(x => x.MessageId, x => x.State, token);
        return rows.Select(x =>
        {
            var transfer = x.VoiceTransferId.HasValue ? transfers.GetValueOrDefault(x.VoiceTransferId.Value) : null;
            return new ChatMessageView(x.Id, x.ConversationId, x.Sequence, x.SenderSubjectId, x.ClientMessageId,
                x.Kind == ChatMessageKind.Text ? "text" : "voice", x.Text, x.VoiceTransferId, transfer?.Sha256,
                transfer?.Length, transfer?.DurationMilliseconds, transfer?.State.ToString(),
                x.ReplyToMessageId, x.ReplyToSenderSubjectId, x.ReplyToKind?.ToString().ToLowerInvariant(),
                x.ReplyToText, x.ReplyToVoiceDurationMilliseconds,
                dispatches.GetValueOrDefault(x.Id, ChatDispatchState.Pending).ToString(), x.CreatedAtUtc);
        }).ToArray();
    }

    private sealed record ReplySnapshot(Guid MessageId, string SenderSubjectId, ChatMessageKind Kind, string? Text,
        int? VoiceDurationMilliseconds);

    private async Task<ReplySnapshot?> ReplySnapshotAsync(Guid applicationId, Guid conversationId, Guid replyToMessageId,
        CancellationToken token)
    {
        var message = await db.ChatMessages.AsNoTracking().SingleOrDefaultAsync(x =>
            x.ApplicationId == applicationId && x.ConversationId == conversationId && x.Id == replyToMessageId, token);
        if (message is null) return null;
        int? duration = null;
        if (message.VoiceTransferId.HasValue)
            duration = await db.ChatVoiceTransfers.AsNoTracking().Where(x =>
                    x.ApplicationId == applicationId && x.Id == message.VoiceTransferId.Value)
                .Select(x => (int?)x.DurationMilliseconds).SingleOrDefaultAsync(token);
        return new ReplySnapshot(message.Id, message.SenderSubjectId, message.Kind, message.Text, duration);
    }

    private async Task<ChatActor?> RequireActorAsync(CancellationToken token) =>
        httpContext.HttpContext is null ? null : await actorResolver.ResolveAsync(httpContext.HttpContext.User, token);

    private async Task<(ChatActor Actor, IChatParticipantDirectory Directory, IChatAccessPolicy Policy)?> RequireContextAsync(CancellationToken token)
    {
        var actor = await RequireActorAsync(token);
        if (actor is null) return null;
        var adapters = registry.Resolve(actor.Application.ApplicationKey);
        return adapters is null || await adapters.Value.Directory.FindBySubjectAsync(actor.Application, actor.SubjectId, token) is null
            ? null : (actor, adapters.Value.Directory, adapters.Value.Policy);
    }
}
