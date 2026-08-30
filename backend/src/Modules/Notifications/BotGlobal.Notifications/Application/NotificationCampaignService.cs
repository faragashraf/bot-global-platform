using System.Data;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.Extensions.Options;

namespace BotGlobal.Notifications.Application;

internal sealed class NotificationCampaignService(
    NotificationsDbContext dbContext,
    IPlatformClientDescriptorReader platformClients,
    IMobileBroadcastAudienceReader audienceReader,
    IOptions<NotificationCampaignOptions> options,
    TimeProvider timeProvider)
    : INotificationCampaignService
{
    public async Task<NotificationAudiencePreviewResponse>
        PreviewAudienceAsync(
            Guid platformClientId,
            CancellationToken cancellationToken)
    {
        if (platformClientId == Guid.Empty)
        {
            throw Validation("platformClientId", "An application is required.");
        }

        var descriptor = await ResolveActiveApplicationAsync(
            platformClientId,
            cancellationToken);

        var asOf = timeProvider.GetUtcNow();
        var preview = await audienceReader.PreviewAsync(
            new NotificationApplicationContext(
                descriptor.PlatformClientId),
            asOf,
            cancellationToken);

        return new NotificationAudiencePreviewResponse(
            descriptor.PlatformClientId,
            descriptor.ClientKey,
            descriptor.DisplayName,
            asOf,
            preview.DistinctExternalSubjectCount,
            preview.ActiveDeviceCount,
            preview.PushCapableDeviceCount);
    }

    public async Task<NotificationCampaignAcceptedResponse> CreateAsync(
        CreateNotificationCampaignCommand command,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(command);

        var normalized = ValidateAndNormalize(command);
        var fingerprint = CreateFingerprint(normalized);
        var descriptor = await ResolveActiveApplicationAsync(
            normalized.PlatformClientId,
            cancellationToken);

        var existing = await FindIdempotentAsync(
            normalized.CreatedByUserId,
            normalized.IdempotencyKey,
            cancellationToken);

        if (existing is not null)
        {
            return ResolveIdempotent(existing, fingerprint);
        }

        var now = timeProvider.GetUtcNow();
        var preview = await audienceReader.PreviewAsync(
            new NotificationApplicationContext(
                descriptor.PlatformClientId),
            now,
            cancellationToken);

        if (preview.ActiveDeviceCount == 0)
        {
            throw new NotificationCampaignConflictException(
                "The selected application has no current active mobile devices.");
        }

        var campaign = NotificationCampaign.Create(
            descriptor.PlatformClientId,
            descriptor.ClientKey,
            descriptor.DisplayName,
            NotificationAudienceKind.AllCurrentActiveDevices,
            now,
            normalized.TitleAr,
            normalized.TitleEn,
            normalized.BodyAr,
            normalized.BodyEn,
            normalized.Type,
            normalized.Priority,
            normalized.IdempotencyKey,
            fingerprint,
            normalized.CreatedByUserId,
            normalized.CreatedByDisplayName,
            now,
            now.AddDays(normalized.LifetimeDays),
            preview.DistinctExternalSubjectCount,
            preview.ActiveDeviceCount,
            preview.PushCapableDeviceCount);

        dbContext.Campaigns.Add(campaign);

        try
        {
            await dbContext.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateException)
        {
            dbContext.ChangeTracker.Clear();

            existing = await FindIdempotentAsync(
                normalized.CreatedByUserId,
                normalized.IdempotencyKey,
                cancellationToken);

            if (existing is null)
            {
                throw;
            }

            return ResolveIdempotent(existing, fingerprint);
        }

        return ToAccepted(campaign);
    }

    public async Task<NotificationCampaignPageResponse> ListAsync(
        NotificationCampaignListQuery query,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(query);
        ArgumentNullException.ThrowIfNull(query.ApplicationScope);

        var page = Math.Max(1, query.Page);
        var pageSize = Math.Clamp(query.PageSize, 1, 100);

        if (query.FromUtc.HasValue && query.ToUtc.HasValue
            && query.FromUtc > query.ToUtc)
        {
            throw Validation(
                "dateRange",
                "The start date must not be after the end date.");
        }

        NotificationCampaignStatus? status = null;
        if (!string.IsNullOrWhiteSpace(query.Status))
        {
            if (!Enum.TryParse<NotificationCampaignStatus>(
                    query.Status,
                    true,
                    out var parsedStatus)
                || !Enum.IsDefined(parsedStatus))
            {
                throw Validation("status", "Campaign status is invalid.");
            }

            status = parsedStatus;
        }

        var campaigns = dbContext.Campaigns.AsNoTracking();

        if (query.ApplicationScope.ApplicationId
            is Guid applicationId)
        {
            var descriptor = await platformClients.FindAsync(
                applicationId,
                cancellationToken);

            if (descriptor is null
                || descriptor.PlatformClientId
                    != applicationId)
            {
                throw Validation(
                    "platformClientId",
                    "The selected application is not recognized.");
            }

            campaigns = campaigns.Where(campaign =>
                campaign.PlatformClientId == descriptor.PlatformClientId);
        }

        if (status.HasValue)
        {
            campaigns = campaigns.Where(campaign =>
                campaign.Status == status.Value);
        }

        if (query.FromUtc.HasValue)
        {
            campaigns = campaigns.Where(campaign =>
                campaign.CreatedAtUtc >= query.FromUtc.Value);
        }

        if (query.ToUtc.HasValue)
        {
            campaigns = campaigns.Where(campaign =>
                campaign.CreatedAtUtc <= query.ToUtc.Value);
        }

        var totalCount = await campaigns.CountAsync(cancellationToken);
        var queuedOrProcessingCount = await campaigns.CountAsync(
            campaign =>
                campaign.Status == NotificationCampaignStatus.Queued
                || campaign.Status == NotificationCampaignStatus.PreparingAudience
                || campaign.Status == NotificationCampaignStatus.Dispatching,
            cancellationToken);
        var completedCount = await campaigns.CountAsync(
            campaign => campaign.Status == NotificationCampaignStatus.Completed,
            cancellationToken);
        var completedWithFailuresOrExpiredCount = await campaigns.CountAsync(
            campaign =>
                campaign.Status == NotificationCampaignStatus.CompletedWithFailures
                || campaign.Status == NotificationCampaignStatus.Expired
                || campaign.Status == NotificationCampaignStatus.Failed,
            cancellationToken);
        var entities = await campaigns
            .OrderByDescending(campaign => campaign.CreatedAtUtc)
            .ThenByDescending(campaign => campaign.Id)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToArrayAsync(cancellationToken);

        return new NotificationCampaignPageResponse(
            entities.Select(ToSummary).ToArray(),
            page,
            pageSize,
            totalCount,
            queuedOrProcessingCount,
            completedCount,
            completedWithFailuresOrExpiredCount);
    }

    public async Task<NotificationCampaignSummaryResponse?> FindAsync(
        ApplicationAdministrationScope applicationScope,
        Guid campaignId,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(applicationScope);

        var campaigns = dbContext.Campaigns.AsNoTracking();
        if (applicationScope.ApplicationId is Guid applicationId)
        {
            var descriptor = await platformClients.FindAsync(
                applicationId,
                cancellationToken);

            if (descriptor is null
                || descriptor.PlatformClientId != applicationId)
            {
                throw Validation(
                    "platformClientId",
                    "The selected application is not recognized.");
            }

            campaigns = campaigns.Where(candidate =>
                candidate.PlatformClientId == applicationId);
        }

        var campaign = await campaigns
            .SingleOrDefaultAsync(
                candidate => candidate.Id == campaignId,
                cancellationToken);

        return campaign is null ? null : ToSummary(campaign);
    }

    public async Task<NotificationCampaignSummaryResponse?> CancelAsync(
        ApplicationAdministrationScope applicationScope,
        Guid campaignId,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(applicationScope);

        if (campaignId == Guid.Empty)
        {
            throw Validation("campaignId", "A campaign is required.");
        }

        if (applicationScope.ApplicationId is not Guid applicationId)
        {
            throw Validation(
                "platformClientId",
                "An explicit application scope is required for cancellation.");
        }

        var descriptor = await platformClients.FindAsync(
            applicationId,
            cancellationToken);

        if (descriptor is null
            || descriptor.PlatformClientId != applicationId)
        {
            throw Validation(
                "platformClientId",
                "The selected application is not recognized.");
        }

        IDbContextTransaction? transaction = null;
        if (dbContext.Database.IsRelational())
        {
            transaction = await dbContext.Database.BeginTransactionAsync(
                IsolationLevel.ReadCommitted,
                cancellationToken);
        }

        try
        {
            NotificationCampaign? campaign;
            if (dbContext.Database.IsSqlServer())
            {
                campaign = await dbContext.Campaigns
                    .FromSqlInterpolated($"""
                        SELECT TOP (1) *
                        FROM [notifications].[NotificationCampaigns]
                            WITH (UPDLOCK, ROWLOCK)
                        WHERE [Id] = {campaignId}
                            AND [PlatformClientId] = {applicationId}
                        """)
                    .SingleOrDefaultAsync(cancellationToken);
            }
            else
            {
                campaign = await dbContext.Campaigns
                    .SingleOrDefaultAsync(candidate =>
                        candidate.Id == campaignId
                        && candidate.PlatformClientId == applicationId,
                        cancellationToken);
            }

            if (campaign is null)
            {
                if (transaction is not null)
                {
                    await transaction.CommitAsync(cancellationToken);
                }

                return null;
            }

            if (campaign.Status is NotificationCampaignStatus.Cancelled
                or NotificationCampaignStatus.Expired)
            {
                if (transaction is not null)
                {
                    await transaction.CommitAsync(cancellationToken);
                }

                return ToSummary(campaign);
            }

            NotificationRecipient[] recipients;
            if (dbContext.Database.IsSqlServer())
            {
                recipients = await dbContext.Recipients
                    .FromSqlInterpolated($"""
                        SELECT recipient.*
                        FROM [notifications].[NotificationRecipients] AS recipient
                            WITH (UPDLOCK, ROWLOCK)
                        WHERE recipient.[CampaignId] = {campaign.Id}
                        """)
                    .ToArrayAsync(cancellationToken);
            }
            else
            {
                recipients = await dbContext.Recipients
                    .Where(recipient => recipient.CampaignId == campaign.Id)
                    .ToArrayAsync(cancellationToken);
            }

            var currentAttemptIds = recipients
                .Where(recipient => recipient.CurrentAttemptId.HasValue)
                .Select(recipient => recipient.CurrentAttemptId!.Value)
                .Distinct()
                .ToArray();
            var attempts = currentAttemptIds.Length == 0
                ? new Dictionary<Guid, NotificationDeliveryAttempt>()
                : await dbContext.DeliveryAttempts
                    .Where(attempt => currentAttemptIds.Contains(attempt.Id))
                    .ToDictionaryAsync(attempt => attempt.Id, cancellationToken);

            var now = timeProvider.GetUtcNow();
            var audienceWasComplete = campaign.IsAudienceExpansionComplete;
            campaign.Cancel(now);

            foreach (var recipient in recipients)
            {
                if (recipient.Status is not NotificationRecipientStatus.Pending
                    and not NotificationRecipientStatus.RetryScheduled)
                {
                    continue;
                }

                if (recipient.CurrentAttemptId is Guid attemptId
                    && attempts.TryGetValue(attemptId, out var attempt)
                    && attempt.Status
                        == NotificationDeliveryAttemptStatus.Prepared)
                {
                    attempt.CancelPrepared(now);
                }

                recipient.Cancel();
            }

            campaign.ApplySummary(
                Count(NotificationRecipientStatus.Pending)
                    + Count(NotificationRecipientStatus.RetryScheduled)
                    + Count(NotificationRecipientStatus.Sending),
                Count(NotificationRecipientStatus.SignalRDispatched),
                Count(NotificationRecipientStatus.FcmAccepted),
                Count(NotificationRecipientStatus.FailedPermanent)
                    + Count(NotificationRecipientStatus.Ambiguous),
                audienceWasComplete
                    ? Count(NotificationRecipientStatus.SkippedRevoked)
                        + Count(NotificationRecipientStatus.Cancelled)
                    : campaign.AudienceDeviceCount,
                Count(NotificationRecipientStatus.Expired),
                now);

            await dbContext.SaveChangesAsync(cancellationToken);
            if (transaction is not null)
            {
                await transaction.CommitAsync(cancellationToken);
            }

            return ToSummary(campaign);

            int Count(NotificationRecipientStatus status) =>
                recipients.Count(recipient => recipient.Status == status);
        }
        catch
        {
            if (transaction is not null)
            {
                await transaction.RollbackAsync(CancellationToken.None);
            }

            throw;
        }
        finally
        {
            if (transaction is not null)
            {
                await transaction.DisposeAsync();
            }
        }
    }

    private async Task<PlatformClientDescriptor>
        ResolveActiveApplicationAsync(
            Guid requestedApplicationId,
            CancellationToken cancellationToken)
    {
        var descriptor = await platformClients.FindAsync(
            requestedApplicationId,
            cancellationToken);

        if (descriptor is null
            || descriptor.PlatformClientId != requestedApplicationId
            || !descriptor.IsActive)
        {
            throw Validation(
                "platformClientId",
                "The selected application is missing or inactive.");
        }

        return descriptor;
    }

    private async Task<NotificationCampaign?> FindIdempotentAsync(
        Guid createdByUserId,
        string idempotencyKey,
        CancellationToken cancellationToken)
    {
        return await dbContext.Campaigns
            .AsNoTracking()
            .SingleOrDefaultAsync(campaign =>
                campaign.CreatedByUserId == createdByUserId
                && campaign.IdempotencyKey == idempotencyKey,
                cancellationToken);
    }

    private static NotificationCampaignAcceptedResponse ResolveIdempotent(
        NotificationCampaign existing,
        string requestFingerprint)
    {
        if (!CryptographicOperations.FixedTimeEquals(
                Encoding.ASCII.GetBytes(existing.RequestFingerprint),
                Encoding.ASCII.GetBytes(requestFingerprint)))
        {
            throw new NotificationCampaignConflictException(
                "The Idempotency-Key was already used for a different campaign request.");
        }

        return ToAccepted(existing);
    }

    private NormalizedCreateCommand ValidateAndNormalize(
        CreateNotificationCampaignCommand command)
    {
        var errors = new Dictionary<string, string[]>(
            StringComparer.OrdinalIgnoreCase);

        if (command.PlatformClientId == Guid.Empty)
        {
            errors["platformClientId"] = ["An application is required."];
        }

        if (command.CreatedByUserId == Guid.Empty)
        {
            errors["createdByUserId"] = ["An authenticated administrator is required."];
        }

        var titleAr = Require(command.TitleAr, "titleAr", 200, errors);
        var titleEn = Require(command.TitleEn, "titleEn", 200, errors);
        var bodyAr = Require(command.BodyAr, "bodyAr", 4000, errors);
        var bodyEn = Require(command.BodyEn, "bodyEn", 4000, errors);
        var type = Require(command.Type, "type", 100, errors);
        var actor = Require(
            command.CreatedByDisplayName,
            "createdByDisplayName",
            200,
            errors);
        var key = Require(command.IdempotencyKey, "idempotencyKey", 200, errors);

        if (!string.Equals(
                command.AudienceKind,
                nameof(NotificationAudienceKind.AllCurrentActiveDevices),
                StringComparison.OrdinalIgnoreCase))
        {
            errors["audienceKind"] = ["The requested audience kind is not supported."];
        }

        if (!Enum.TryParse<NotificationPriority>(
                command.Priority,
                true,
                out var priority)
            || !Enum.IsDefined(priority))
        {
            errors["priority"] = ["Priority must be Normal or High."];
        }

        var configured = options.Value;
        var lifetimeDays = command.LifetimeDays
            ?? configured.DefaultCampaignLifetimeDays;

        var maximumLifetime = Math.Min(
            configured.MaximumCampaignLifetimeDays,
            28);

        if (lifetimeDays < configured.MinimumCampaignLifetimeDays
            || lifetimeDays > maximumLifetime)
        {
            errors["lifetimeDays"] =
            [
                $"Lifetime must be between {configured.MinimumCampaignLifetimeDays} and {maximumLifetime} days."
            ];
        }

        if (errors.Count > 0)
        {
            throw new NotificationCampaignValidationException(errors);
        }

        return new NormalizedCreateCommand(
            command.PlatformClientId,
            titleAr,
            titleEn,
            bodyAr,
            bodyEn,
            type,
            priority,
            lifetimeDays,
            nameof(NotificationAudienceKind.AllCurrentActiveDevices),
            key,
            command.CreatedByUserId,
            actor);
    }

    private static string Require(
        string? value,
        string field,
        int maximumLength,
        IDictionary<string, string[]> errors)
    {
        var normalized = value?.Trim() ?? string.Empty;

        if (normalized.Length == 0)
        {
            errors[field] = ["This field is required."];
        }
        else if (normalized.Length > maximumLength)
        {
            errors[field] = [$"This field cannot exceed {maximumLength} characters."];
        }

        return normalized;
    }

    private static NotificationCampaignValidationException Validation(
        string field,
        string message)
    {
        return new NotificationCampaignValidationException(
            new Dictionary<string, string[]>
            {
                [field] = [message]
            });
    }

    private static string CreateFingerprint(NormalizedCreateCommand command)
    {
        var json = JsonSerializer.Serialize(new
        {
            command.PlatformClientId,
            command.TitleAr,
            command.TitleEn,
            command.BodyAr,
            command.BodyEn,
            command.Type,
            command.Priority,
            command.LifetimeDays,
            command.AudienceKind
        });

        return Convert.ToHexString(
            SHA256.HashData(Encoding.UTF8.GetBytes(json)));
    }

    private static NotificationCampaignAcceptedResponse ToAccepted(
        NotificationCampaign campaign)
    {
        var actual = campaign.PendingCount
            + campaign.SignalRDispatchedCount
            + campaign.FcmAcceptedCount
            + campaign.FailedCount
            + campaign.SkippedCount
            + campaign.ExpiredCount;

        return new NotificationCampaignAcceptedResponse(
            campaign.Id,
            campaign.Status.ToString(),
            campaign.AudienceAsOfUtc,
            campaign.AudienceSubjectCount,
            campaign.AudienceDeviceCount,
            actual,
            campaign.CreatedAtUtc,
            campaign.ExpiresAtUtc);
    }

    private static NotificationCampaignSummaryResponse ToSummary(
        NotificationCampaign campaign)
    {
        return new NotificationCampaignSummaryResponse(
            campaign.Id,
            campaign.PlatformClientId,
            campaign.PlatformClientKeySnapshot,
            campaign.PlatformClientDisplayNameSnapshot,
            campaign.AudienceKind.ToString(),
            campaign.Priority.ToString(),
            campaign.Type,
            campaign.Status.ToString(),
            campaign.AudienceAsOfUtc,
            campaign.CreatedAtUtc,
            campaign.ExpiresAtUtc,
            campaign.ProcessingStartedAtUtc,
            campaign.CompletedAtUtc,
            campaign.CreatedByDisplayNameSnapshot,
            campaign.AudienceSubjectCount,
            campaign.AudienceDeviceCount,
            campaign.PushCapableDeviceCount,
            campaign.PendingCount,
            campaign.SignalRDispatchedCount,
            campaign.FcmAcceptedCount,
            campaign.FailedCount,
            campaign.SkippedCount,
            campaign.ExpiredCount);
    }

    private sealed record NormalizedCreateCommand(
        Guid PlatformClientId,
        string TitleAr,
        string TitleEn,
        string BodyAr,
        string BodyEn,
        string Type,
        NotificationPriority Priority,
        int LifetimeDays,
        string AudienceKind,
        string IdempotencyKey,
        Guid CreatedByUserId,
        string CreatedByDisplayName);
}
