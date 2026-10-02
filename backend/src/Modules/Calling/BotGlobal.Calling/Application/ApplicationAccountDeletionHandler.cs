using BotGlobal.Calling.Infrastructure;
using BotGlobal.Calling.Realtime;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Calling.Application;

internal sealed class CallingAccountDeletionHandler(
    CallSessionRegistry sessions,
    NqrbGuestCallInviteService guestInvites,
    ICallActivityService activity,
    IHubContext<CallingHub> hub,
    CallingAccountDataEraser dataEraser,
    TimeProvider timeProvider) : IApplicationAccountDeletionHandler
{
    public string StepName => "calling";
    public int Order => 100;
    public bool RevokesAccess => true;

    public async Task DeleteAsync(ApplicationAccountDeletionScope scope, CancellationToken cancellationToken)
    {
        if (scope.Identity.ApplicationKey == BotGlobalApplications.Nqrb)
            guestInvites.RevokeHostInvites(scope.Identity.MembershipId);

        foreach (var transition in sessions.BlockMembership(
                     scope.Identity.MembershipId,
                     scope.Identity.ApplicationKey))
        {
            if (transition.Session.IsGuestCall)
                guestInvites.Complete(transition.Session.GuestInviteId);
            else
                await activity.FinishAsync(transition.Session, timeProvider.GetUtcNow(), cancellationToken);
            foreach (var peer in transition.PeerConnections)
                await hub.Clients.Client(peer.ConnectionId).SendAsync(
                    "CallEnded",
                    new CallEndedEvent(transition.Session.CallId, "account_deleted"),
                    cancellationToken);
        }

        await dataEraser.DeleteAsync(
            scope.Identity.ApplicationKey,
            scope.Identity.MembershipId,
            cancellationToken);
    }
}

internal sealed class CallingAccountDataEraser(CallingDbContext dbContext)
{
    public async Task DeleteAsync(
        string applicationKey,
        Guid membershipId,
        CancellationToken cancellationToken)
    {
        var periods = await dbContext.UsagePeriods
            .Where(item => item.MembershipId == membershipId)
            .ToListAsync(cancellationToken);
        var contactEdges = await dbContext.NqrbContactEdges
            .Where(item =>
                item.ApplicationKey == applicationKey &&
                (item.OwnerMembershipId == membershipId ||
                 item.ContactMembershipId == membershipId))
            .ToListAsync(cancellationToken);
        var contactInvites = await dbContext.NqrbContactInvites
            .Where(item =>
                item.ApplicationKey == applicationKey &&
                (item.IssuerMembershipId == membershipId ||
                 item.ClaimedByMembershipId == membershipId))
            .ToListAsync(cancellationToken);
        var blockedAccounts = await dbContext.NqrbBlockedAccounts
            .Where(item => item.ApplicationKey == applicationKey &&
                (item.OwnerMembershipId == membershipId || item.BlockedMembershipId == membershipId))
            .ToListAsync(cancellationToken);
        var calls = await dbContext.Calls
            .Include(item => item.Participants)
            .Include(item => item.UsageReports)
            .Where(item => item.ApplicationKey == applicationKey
                && item.Participants.Any(participant => participant.MembershipId == membershipId))
            .ToListAsync(cancellationToken);

        dbContext.UsagePeriods.RemoveRange(periods);
        dbContext.NqrbContactEdges.RemoveRange(contactEdges);
        dbContext.NqrbContactInvites.RemoveRange(contactInvites);
        dbContext.NqrbBlockedAccounts.RemoveRange(blockedAccounts);
        foreach (var call in calls)
        {
            if (call.IsGuestCall || call.Participants.Count == 1)
            {
                dbContext.Calls.Remove(call);
                continue;
            }

            dbContext.UsageReports.RemoveRange(
                call.UsageReports.Where(item => item.MembershipId == membershipId));
            foreach (var participant in call.Participants.Where(
                         item => item.MembershipId == membershipId).ToArray())
            {
                dbContext.Participants.Remove(participant);
                dbContext.Participants.Add(participant.CreateAnonymized(Guid.NewGuid()));
            }
        }

        await dbContext.SaveChangesAsync(cancellationToken);
    }
}
