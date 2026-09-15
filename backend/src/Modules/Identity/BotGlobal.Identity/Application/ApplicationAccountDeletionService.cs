using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Identity.Application;

public enum ApplicationAccountDeletionOutcome
{
    Completed,
    Accepted,
    RetryableFailure
}

internal enum ApplicationAccountDeletionProcessOutcome
{
    Completed,
    AccessRevokedPending,
    BusyBeforeAccessRevocation
}

public interface IApplicationAccountDeletionService
{
    Task<ApplicationAccountDeletionOutcome> DeleteAsync(
        ApplicationIdentityDescriptor identity,
        CancellationToken cancellationToken);
}

internal sealed class ApplicationAccountDeletionService(
    IdentityDbContext dbContext,
    ApplicationAccountDeletionProcessor processor,
    TimeProvider timeProvider) : IApplicationAccountDeletionService
{
    public async Task<ApplicationAccountDeletionOutcome> DeleteAsync(
        ApplicationIdentityDescriptor identity,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(identity);
        if (identity.GlobalUserId is not Guid globalUserId || identity.IsGuest)
        {
            throw new InvalidOperationException("An authenticated non-guest account is required.");
        }

        var operation = await dbContext.AccountDeletionRequests.SingleOrDefaultAsync(
            item => item.MembershipId == identity.MembershipId,
            cancellationToken);

        if (operation is null)
        {
            var membership = await dbContext.ApplicationMemberships.SingleOrDefaultAsync(
                item => item.Id == identity.MembershipId
                    && item.GlobalUserId == globalUserId
                    && item.ApplicationKey == identity.ApplicationKey
                    && item.SubjectId == identity.SubjectId,
                cancellationToken);
            if (membership is null)
            {
                var anotherMembershipForApplication = await dbContext.ApplicationMemberships.AnyAsync(
                    item => item.GlobalUserId == globalUserId
                        && item.ApplicationKey == identity.ApplicationKey,
                    cancellationToken);
                if (!anotherMembershipForApplication)
                {
                    return ApplicationAccountDeletionOutcome.Completed;
                }

                throw new InvalidOperationException("Authenticated application membership is unavailable.");
            }

            var now = timeProvider.GetUtcNow();
            operation = new ApplicationAccountDeletionRequest(
                Guid.NewGuid(),
                membership.Id,
                globalUserId,
                membership.ApplicationKey,
                membership.SubjectId,
                [],
                now);
            dbContext.AccountDeletionRequests.Add(operation);
            try
            {
                await dbContext.SaveChangesAsync(cancellationToken);
            }
            catch (DbUpdateException)
            {
                dbContext.ChangeTracker.Clear();
                operation = await dbContext.AccountDeletionRequests.SingleOrDefaultAsync(
                    item => item.MembershipId == identity.MembershipId,
                    cancellationToken);
                if (operation is null)
                {
                    throw;
                }
            }
        }

        return await processor.TryProcessAsync(operation.Id, cancellationToken) switch
        {
            ApplicationAccountDeletionProcessOutcome.Completed =>
                ApplicationAccountDeletionOutcome.Completed,
            ApplicationAccountDeletionProcessOutcome.AccessRevokedPending =>
                ApplicationAccountDeletionOutcome.Accepted,
            _ => ApplicationAccountDeletionOutcome.RetryableFailure
        };
    }
}

internal sealed class ApplicationAccountDeletionProcessor(
    IdentityDbContext dbContext,
    IEnumerable<IApplicationAccountDeletionHandler> handlers,
    TimeProvider timeProvider,
    ILogger<ApplicationAccountDeletionProcessor> logger)
{
    private static readonly TimeSpan RetryDelay = TimeSpan.FromMinutes(1);
    private static readonly TimeSpan ProcessorLeaseDuration = TimeSpan.FromMinutes(15);

    public async Task<ApplicationAccountDeletionProcessOutcome> TryProcessAsync(
        Guid operationId,
        CancellationToken cancellationToken)
    {
        var operation = await dbContext.AccountDeletionRequests.SingleOrDefaultAsync(
            item => item.Id == operationId,
            cancellationToken);
        if (operation is null)
        {
            return ApplicationAccountDeletionProcessOutcome.Completed;
        }

        var leaseId = Guid.NewGuid();
        var leaseNow = timeProvider.GetUtcNow();
        if (!operation.TryAcquireLease(leaseId, leaseNow, ProcessorLeaseDuration))
        {
            return OutcomeForPersistedAccessState(operation);
        }

        try
        {
            await dbContext.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateConcurrencyException)
        {
            dbContext.ChangeTracker.Clear();
            operation = await dbContext.AccountDeletionRequests
                .AsNoTracking()
                .SingleOrDefaultAsync(item => item.Id == operationId, cancellationToken);
            return operation is null
                ? ApplicationAccountDeletionProcessOutcome.Completed
                : OutcomeForPersistedAccessState(operation);
        }

        try
        {
            var now = timeProvider.GetUtcNow();
            await RevokeIdentityAccessAsync(operation, now, cancellationToken);
            operation.MarkAttempt(now);
            operation.RenewLease(leaseId, now, ProcessorLeaseDuration);
            await dbContext.SaveChangesAsync(cancellationToken);

            var orderedHandlers = handlers
                .OrderBy(item => item.Order)
                .ThenBy(item => item.StepName, StringComparer.Ordinal)
                .ToArray();
            var finalAccessRevocationHandler = orderedHandlers.LastOrDefault(item => item.RevokesAccess);
            if (finalAccessRevocationHandler is null)
            {
                operation.MarkAccessRevoked(now);
                await dbContext.SaveChangesAsync(cancellationToken);
            }

            var scope = new ApplicationAccountDeletionScope(
                new ApplicationAccountDeletionIdentity(
                    operation.MembershipId,
                    operation.GlobalUserId,
                    operation.SubjectId,
                    operation.ApplicationKey),
                operation.MobileDeviceIds());
            foreach (var handler in orderedHandlers)
            {
                await handler.DeleteAsync(scope, cancellationToken);
                operation.IncludeMobileDeviceIds(scope.MobileDeviceIds);
                if (ReferenceEquals(handler, finalAccessRevocationHandler))
                {
                    operation.MarkAccessRevoked(timeProvider.GetUtcNow());
                }
                operation.RenewLease(
                    leaseId,
                    timeProvider.GetUtcNow(),
                    ProcessorLeaseDuration);
                await dbContext.SaveChangesAsync(cancellationToken);
            }

            await FinalizeIdentityAsync(operation, cancellationToken);
            return ApplicationAccountDeletionProcessOutcome.Completed;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception error)
        {
            logger.LogWarning(
                "Application account deletion step failed and will be retried. ErrorType={ErrorType}",
                error.GetType().Name);
            dbContext.ChangeTracker.Clear();
            var persisted = await dbContext.AccountDeletionRequests.SingleOrDefaultAsync(
                item => item.Id == operationId,
                CancellationToken.None);
            if (persisted is null)
            {
                return ApplicationAccountDeletionProcessOutcome.Completed;
            }

            if (persisted.ProcessorLeaseId == leaseId)
            {
                persisted.MarkRetry(
                    timeProvider.GetUtcNow().Add(RetryDelay),
                    "account-deletion-step-failed");
                await dbContext.SaveChangesAsync(CancellationToken.None);
            }

            return OutcomeForPersistedAccessState(persisted);
        }
    }

    public async Task ProcessPendingAsync(CancellationToken cancellationToken)
    {
        var now = timeProvider.GetUtcNow();
        var ids = await dbContext.AccountDeletionRequests
            .AsNoTracking()
            .Where(item => item.NextAttemptAtUtc <= now
                && (item.ProcessorLeaseExpiresAtUtc == null
                    || item.ProcessorLeaseExpiresAtUtc <= now))
            .OrderBy(item => item.RequestedAtUtc)
            .Select(item => item.Id)
            .Take(25)
            .ToListAsync(cancellationToken);
        foreach (var id in ids)
        {
            await TryProcessAsync(id, cancellationToken);
        }
    }

    private static ApplicationAccountDeletionProcessOutcome OutcomeForPersistedAccessState(
        ApplicationAccountDeletionRequest operation) =>
        operation.AccessRevokedAtUtc is not null
            ? ApplicationAccountDeletionProcessOutcome.AccessRevokedPending
            : ApplicationAccountDeletionProcessOutcome.BusyBeforeAccessRevocation;

    private async Task RevokeIdentityAccessAsync(
        ApplicationAccountDeletionRequest operation,
        DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        var membership = await dbContext.ApplicationMemberships.SingleOrDefaultAsync(
            item => item.Id == operation.MembershipId,
            cancellationToken);
        membership?.Deactivate();

        var sessions = await dbContext.MobileApplicationSessions
            .Where(item => item.MembershipId == operation.MembershipId)
            .ToListAsync(cancellationToken);
        foreach (var session in sessions) session.Revoke(now);

        await dbContext.SaveChangesAsync(cancellationToken);
    }

    private async Task FinalizeIdentityAsync(
        ApplicationAccountDeletionRequest operation,
        CancellationToken cancellationToken)
    {
        var membership = await dbContext.ApplicationMemberships.SingleOrDefaultAsync(
            item => item.Id == operation.MembershipId,
            cancellationToken);
        if (membership is not null)
        {
            dbContext.ApplicationMemberships.Remove(membership);
        }

        var hasOtherMembership = await dbContext.ApplicationMemberships.AnyAsync(
            item => item.GlobalUserId == operation.GlobalUserId && item.Id != operation.MembershipId,
            cancellationToken);
        if (!hasOtherMembership)
        {
            var user = await dbContext.Users.SingleOrDefaultAsync(
                item => item.Id == operation.GlobalUserId,
                cancellationToken);
            if (user is not null)
            {
                dbContext.Users.Remove(user);
            }
        }

        dbContext.AccountDeletionRequests.Remove(operation);
        await dbContext.SaveChangesAsync(cancellationToken);
    }
}

internal sealed class ApplicationMembershipActivityReader(IdentityDbContext dbContext)
    : IApplicationMembershipActivityReader
{
    public Task<bool> IsActiveAsync(
        Guid membershipId,
        string applicationKey,
        CancellationToken cancellationToken) =>
        dbContext.ApplicationMemberships
            .AsNoTracking()
            .AnyAsync(
                membership => membership.Id == membershipId
                    && membership.ApplicationKey == applicationKey
                    && membership.IsActive,
                cancellationToken);

    public Task<bool> IsActiveAsync(
        Guid membershipId,
        string applicationKey,
        string subjectId,
        CancellationToken cancellationToken) =>
        dbContext.ApplicationMemberships
            .AsNoTracking()
            .AnyAsync(
                membership => membership.Id == membershipId
                    && membership.ApplicationKey == applicationKey
                    && membership.SubjectId == subjectId
                    && membership.IsActive,
                cancellationToken);
}
