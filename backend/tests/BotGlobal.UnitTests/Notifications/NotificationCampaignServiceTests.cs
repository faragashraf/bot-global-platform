using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationCampaignServiceTests
{
    [Fact]
    public async Task Active_platform_client_is_required()
    {
        await using var db = CreateContext();
        var missing = CreateService(db, descriptor: null);

        var missingError = await Assert.ThrowsAsync<NotificationCampaignValidationException>(
            () => missing.CreateAsync(Command(), CancellationToken.None));
        Assert.Contains("platformClientId", missingError.Errors.Keys);

        var inactiveDescriptor = new PlatformClientDescriptor(
            Command().PlatformClientId,
            "inactive-app",
            "Inactive app",
            false);
        var inactive = CreateService(db, inactiveDescriptor);

        await Assert.ThrowsAsync<NotificationCampaignValidationException>(
            () => inactive.CreateAsync(Command(), CancellationToken.None));
    }

    [Fact]
    public async Task Client_cannot_spoof_application_id_with_a_mismatched_descriptor()
    {
        await using var db = CreateContext();
        var command = Command();
        var service = CreateService(
            db,
            new PlatformClientDescriptor(
                Guid.NewGuid(),
                "different-app",
                "Different application",
                true));

        var error =
            await Assert.ThrowsAsync<NotificationCampaignValidationException>(
                () => service.CreateAsync(
                    command,
                    CancellationToken.None));

        Assert.Contains("platformClientId", error.Errors.Keys);
        Assert.Empty(db.Campaigns);
    }

    [Fact]
    public async Task Unrecognized_application_filter_is_rejected()
    {
        await using var db = CreateContext();
        var service = CreateService(db, descriptor: null);

        var error =
            await Assert.ThrowsAsync<NotificationCampaignValidationException>(
                () => service.ListAsync(
                    new NotificationCampaignListQuery(
                        ApplicationAdministrationScope.ForApplication(
                            Guid.NewGuid()),
                        null,
                        null,
                        null,
                        1,
                        25),
                    CancellationToken.None));

        Assert.Contains("platformClientId", error.Errors.Keys);
    }

    [Fact]
    public async Task Active_platform_client_and_nonempty_audience_are_durably_accepted()
    {
        await using var db = CreateContext();
        var command = Command();
        var descriptor = new PlatformClientDescriptor(
            command.PlatformClientId,
            "enpo-connect",
            "ENPO Connect",
            true);
        var service = CreateService(db, descriptor);

        var response = await service.CreateAsync(command, CancellationToken.None);

        Assert.Equal("Queued", response.Status);
        Assert.Equal(3, response.ExpectedSubjectCount);
        Assert.Equal(5, response.ExpectedDeviceCount);
        Assert.Equal(0, response.ActualRecipientCount);
        Assert.Equal(1, await db.Campaigns.CountAsync());
    }

    [Fact]
    public async Task Zero_recipient_audience_is_an_explicit_conflict()
    {
        await using var db = CreateContext();
        var command = Command();
        var service = CreateService(
            db,
            new PlatformClientDescriptor(
                command.PlatformClientId,
                "empty-app",
                "Empty app",
                true),
            new MobileBroadcastAudiencePreview(0, 0, 0));

        await Assert.ThrowsAsync<NotificationCampaignConflictException>(
            () => service.CreateAsync(command, CancellationToken.None));
        Assert.Empty(db.Campaigns);
    }

    [Fact]
    public async Task Duplicate_idempotent_post_returns_same_campaign()
    {
        await using var db = CreateContext();
        var command = Command();
        var descriptor = new PlatformClientDescriptor(
            command.PlatformClientId,
            "app-key",
            "Application",
            true);
        var service = CreateService(db, descriptor);

        var first = await service.CreateAsync(command, CancellationToken.None);
        var second = await service.CreateAsync(command, CancellationToken.None);

        Assert.Equal(first.CampaignId, second.CampaignId);
        Assert.Equal(1, await db.Campaigns.CountAsync());
    }

    [Fact]
    public async Task Reusing_idempotency_key_for_different_payload_is_rejected()
    {
        await using var db = CreateContext();
        var command = Command();
        var descriptor = new PlatformClientDescriptor(
            command.PlatformClientId,
            "app-key",
            "Application",
            true);
        var service = CreateService(db, descriptor);
        await service.CreateAsync(command, CancellationToken.None);

        await Assert.ThrowsAsync<NotificationCampaignConflictException>(() =>
            service.CreateAsync(
                command with { BodyEn = "A different message" },
                CancellationToken.None));
    }

    [Theory]
    [InlineData("", "English title", "Arabic body", "English body")]
    [InlineData("Arabic title", "", "Arabic body", "English body")]
    [InlineData("Arabic title", "English title", "", "English body")]
    [InlineData("Arabic title", "English title", "Arabic body", "")]
    public async Task Bilingual_fields_are_required(
        string titleAr,
        string titleEn,
        string bodyAr,
        string bodyEn)
    {
        await using var db = CreateContext();
        var command = Command() with
        {
            TitleAr = titleAr,
            TitleEn = titleEn,
            BodyAr = bodyAr,
            BodyEn = bodyEn
        };
        var service = CreateService(
            db,
            new PlatformClientDescriptor(
                command.PlatformClientId,
                "app-key",
                "Application",
                true));

        await Assert.ThrowsAsync<NotificationCampaignValidationException>(
            () => service.CreateAsync(command, CancellationToken.None));
    }

    private static NotificationCampaignService CreateService(
        NotificationsDbContext db,
        PlatformClientDescriptor? descriptor,
        MobileBroadcastAudiencePreview? preview = null)
    {
        return new NotificationCampaignService(
            db,
            new DescriptorReader(descriptor),
            new AudienceReader(preview ?? new MobileBroadcastAudiencePreview(3, 5, 4)),
            Options.Create(new NotificationCampaignOptions()),
            new FixedTimeProvider(new DateTimeOffset(2026, 8, 21, 12, 0, 0, TimeSpan.Zero)));
    }

    private static CreateNotificationCampaignCommand Command()
    {
        return new CreateNotificationCampaignCommand(
            Guid.Parse("8a0bb3f5-e4ea-4f10-90dd-dadcb85f9a1b"),
            "عنوان عربي",
            "English title",
            "محتوى عربي",
            "English body",
            "general",
            "Normal",
            7,
            "AllCurrentActiveDevices",
            "0a870087-a372-4d96-8fa9-20ac42d4faca",
            Guid.Parse("9fbfc5c8-41ae-4442-b1eb-3da876b12c76"),
            "Admin User");
    }

    private static NotificationsDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<NotificationsDbContext>()
            .UseInMemoryDatabase($"campaign-service-{Guid.NewGuid():N}")
            .Options;
        return new NotificationsDbContext(options);
    }

    private sealed class DescriptorReader(PlatformClientDescriptor? descriptor)
        : IPlatformClientDescriptorReader
    {
        public Task<PlatformClientDescriptor?> FindAsync(Guid platformClientId, CancellationToken cancellationToken) =>
            Task.FromResult(descriptor);
    }

    private sealed class AudienceReader(MobileBroadcastAudiencePreview preview)
        : IMobileBroadcastAudienceReader
    {
        public Task<MobileBroadcastAudiencePreview> PreviewAsync(NotificationApplicationContext application, DateTimeOffset audienceAsOfUtc, CancellationToken cancellationToken) => Task.FromResult(preview);
        public Task<MobileBroadcastAudiencePage> ReadPageAsync(NotificationApplicationContext application, DateTimeOffset audienceAsOfUtc, Guid? afterDeviceId, int pageSize, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<MobileBroadcastDeviceState> GetCurrentDeviceStateAsync(NotificationApplicationContext application, Guid deviceId, CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
