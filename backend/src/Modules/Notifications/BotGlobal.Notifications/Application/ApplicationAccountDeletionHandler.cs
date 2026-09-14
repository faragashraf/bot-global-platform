using BotGlobal.Contracts.Mobile;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Notifications.Application;

internal sealed class NotificationAccountDeletionHandler(
    NotificationsDbContext dbContext) : IApplicationAccountDeletionHandler
{
    public string StepName => "notifications";
    public int Order => 200;

    public async Task DeleteAsync(ApplicationAccountDeletionScope scope, CancellationToken cancellationToken)
    {
        if (scope.MobileDeviceIds.Count == 0) return;
        var recipients = await dbContext.Recipients
            .Include(item => item.DeliveryAttempts)
            .Where(item => scope.MobileDeviceIds.Contains(item.MobileDeviceId))
            .ToListAsync(cancellationToken);
        dbContext.Recipients.RemoveRange(recipients);
        await dbContext.SaveChangesAsync(cancellationToken);
    }
}
