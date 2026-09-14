using BotGlobal.Calling.Infrastructure;
using BotGlobal.Calling.Realtime;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Calling.Application;

internal sealed class CallingAccountDeletionHandler(
    CallSessionRegistry sessions,
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
        foreach (var transition in sessions.BlockMembership(
                     scope.Identity.MembershipId,
                     scope.Identity.ApplicationKey))
        {
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
        var calls = await dbContext.Calls
            .Include(item => item.Participants)
            .Include(item => item.UsageReports)
            .Where(item => item.ApplicationKey == applicationKey
                && item.Participants.Any(participant => participant.MembershipId == membershipId))
            .ToListAsync(cancellationToken);

        dbContext.UsagePeriods.RemoveRange(periods);
        foreach (var call in calls)
        {
            if (call.Participants.Count == 1)
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
