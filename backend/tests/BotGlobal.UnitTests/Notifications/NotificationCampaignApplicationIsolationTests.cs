using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationCampaignApplicationIsolationTests
{
    [Fact]
    public async Task Campaign_summaries_and_details_respect_explicit_application_scope()
    {
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        var now = DateTimeOffset.UtcNow;
        await using var db = CreateContext();
        var campaignA = Campaign(appA, "app-a", now);
        var campaignB = Campaign(appB, "app-b", now.AddMinutes(1));
        db.Campaigns.AddRange(campaignA, campaignB);
        await db.SaveChangesAsync();
        var service = new NotificationCampaignService(
            db,
            new DescriptorReader(appA, appB),
            new EmptyAudienceReader(),
            Options.Create(new NotificationCampaignOptions()),
            TimeProvider.System);

        var scopedPage = await service.ListAsync(
            Query(ApplicationAdministrationScope.ForApplication(appA)),
            CancellationToken.None);
        var crossApplicationDetail = await service.FindAsync(
            ApplicationAdministrationScope.ForApplication(appA),
            campaignB.Id,
            CancellationToken.None);
        var globalPage = await service.ListAsync(
            Query(ApplicationAdministrationScope.PlatformGlobal),
            CancellationToken.None);

        var scoped = Assert.Single(scopedPage.Items);
        Assert.Equal(appA, scoped.PlatformClientId);
        Assert.Null(crossApplicationDetail);
        Assert.Equal(2, globalPage.TotalCount);
    }

    private static NotificationCampaignListQuery Query(
        ApplicationAdministrationScope scope) =>
        new(scope, null, null, null, 1, 25);

    private static NotificationCampaign Campaign(
        Guid applicationId,
        string key,
        DateTimeOffset now) =>
        NotificationCampaign.Create(
            applicationId,
            key,
            key,
            NotificationAudienceKind.AllCurrentActiveDevices,
            now,
            "عنوان",
            "Title",
            "نص",
            "Body",
            "general",
            NotificationPriority.Normal,
            Guid.NewGuid().ToString("N"),
            new string('A', 64),
            Guid.NewGuid(),
            "Administrator",
            now,
            now.AddDays(7),
            1,
            1,
            1);

    private static NotificationsDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<NotificationsDbContext>()
            .UseInMemoryDatabase(
                $"campaign-application-isolation-{Guid.NewGuid():N}")
            .Options;
        return new NotificationsDbContext(options);
    }

    private sealed class DescriptorReader(params Guid[] applicationIds)
        : IPlatformClientDescriptorReader
    {
        public Task<PlatformClientDescriptor?> FindAsync(
            Guid platformClientId,
            CancellationToken cancellationToken)
        {
            var descriptor = applicationIds.Contains(platformClientId)
                ? new PlatformClientDescriptor(
                    platformClientId,
                    $"app-{platformClientId:N}",
                    "Application",
                    true)
                : null;

            return Task.FromResult(descriptor);
        }
    }

    private sealed class EmptyAudienceReader
        : IMobileBroadcastAudienceReader
    {
        public Task<MobileBroadcastAudiencePreview> PreviewAsync(
            NotificationApplicationContext application,
            DateTimeOffset audienceAsOfUtc,
            CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<MobileBroadcastAudiencePage> ReadPageAsync(
            NotificationApplicationContext application,
            DateTimeOffset audienceAsOfUtc,
            Guid? afterDeviceId,
            int pageSize,
            CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<MobileBroadcastDeviceState> GetCurrentDeviceStateAsync(
            NotificationApplicationContext application,
            Guid deviceId,
            CancellationToken cancellationToken) =>
            throw new NotSupportedException();
    }
}
